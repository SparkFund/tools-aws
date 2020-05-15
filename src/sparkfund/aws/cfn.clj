(ns sparkfund.aws.cfn
  "uses cognitect aws api to perform cloud formation operations"
  (:require [clojure.data]
            [clojure.data.json]
            [clojure.string :as string]
            [cognitect.aws.client.api :as aws]
            [sparkfund.cli.build-info :refer [build-info]]
            [sparkfund.cli.color :as color]
            [sparkfund.cli.prompts :as prompt]
            [sparkfund.cli.style :as style]
            [sparkfund.cli.util :as cli]
            [sparkfund.aws.cfn.model :as model]
            [sparkfund.aws.cfn.templating :as templating])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)))

(defn invoke!
  "aws/invoke that raises an error on :ErrorResponse"
  [& args]
  (let [response (apply aws/invoke args)]
    (when (contains? response :ErrorResponse)
      (throw (ex-info "error code" response)))
    response))

(defn get-params
  "determines the parameter Key and Values from a stack description,
  considering the constants"
  [model stack-desc]
  (merge
   ;; stuff that should always be true
   (get (:constants model) true)
   ;; stuff from this specific instance
   (into
    {}
    (clojure.walk/postwalk
     (fn [x]
       (cond (and (seq? x)
                  (= 'case (first x)))
             (eval `(case ~(get stack-desc (second x))
                      ~@(drop 2 x)))
             (and (seq? x)
                  (= 'merge (first x)))
             (eval x)
             :else x))
     (map (fn [[k v]]
            (get (:constants model) [k v]))
          stack-desc)))
   ;; stuff from this specific stack
   (:params stack-desc)
   ))

(defn simplify-template-diff
  [a e path]
  (cond
    ;; special case: this metadata retrieve drops a / for some reason
    (and (= (take-last 3 path) ["AWS::CloudFormation::Init" "config" "files"])
         (map? a) (map? e)
         (= (into {} (map (fn [[k v]] [(str "/" k) v])) a) e))
    []
    (and (map? a) (map? e))
    (mapcat (fn [k] (simplify-template-diff (get a k) (get e k) (conj path k)))
            (into #{} (concat (keys a) (keys e))))
    (and (vector? a) (vector? e)
         (= (count a) (count e)))
    (mapcat (fn [i a' e']
              (simplify-template-diff a' e' (conj path i)))
            (range) a e)
    (not= a e)
    [{:path path
      :actual a
      :expected e}]))

(defn explain-template-diff
  "explains the difference between two templates"
  [actual expected]
  (let [[a e] (clojure.data/diff actual expected)]
    (simplify-template-diff a e [])))

(defn compute-template
  [cf actual-stack stack-desc template]
  (let [response (when (some? actual-stack)
                   (aws/invoke cf {:op :GetTemplate
                                   :request {:StackName (:name stack-desc)}}))
        actual-template-body (-> (:TemplateBody response (str {}))
                                 clojure.data.json/read-str)
        template-body (-> template
                          clojure.walk/stringify-keys
                          clojure.data.json/write-str)]
    {:explanation (explain-template-diff actual-template-body
                                         template)
     :template-body template-body}))

(defn print-template-diff
  [diff-explanation]
  (if (empty? diff-explanation)
    (println (color/green "  No difference detected in template"))
    (doseq [{:keys [:actual :expected :path]} diff-explanation]
      (println " Path: " (style/path (clojure.string/join " > " path)))
      (binding [*print-length* 3]
        (println (color/cyan (str "   CLOUD: " actual)))
        (println (color/yellow (str "   LOCAL: " expected)))))))

(defn compute-params
  "computes a diff between the parameters of the actual stack, the
  description of the stack, and the parameters in the template

  returns :params, those parameters that are used, and :missing,
  parameters that the template is expecting but are missing"
  [model actual-stack stack-desc template-map]
  (let [computed-params (get-params model stack-desc)
        existing-params (into {}
                              (map (fn [param]
                                     [(:ParameterKey param)
                                      (:ParameterValue param)]))
                              (:Parameters actual-stack))
        param-desc (into
                    []
                    (map (fn [[k v]]
                           (let [existing (get existing-params k)
                                 computed (get computed-params k)
                                 default (get v "Default")]
                             (into
                              {}
                              (filter (comp some? val))
                              {:key k
                               :no-echo? (= "true" (get v "NoEcho"))
                               :computed computed
                               :default default
                               :existing existing}))))
                    (get template-map "Parameters"))
        params (into
                []
                (keep (fn [{:keys [:key :computed :existing]}]
                        (cond
                          (and (contains? existing-params key)
                               (or (not (some? computed))
                                   (= existing computed)))
                          {:ParameterKey key
                           :UsePreviousValue true}
                          (some? computed)
                          {:ParameterKey key
                           :ParameterValue computed})))
                param-desc)]
    {:params params
     :param-desc param-desc}))

(defn unpaginate
  [cf op-map]
  (loop [next-token nil
         responses []]
    (let [response (invoke! cf (cond-> op-map
                                 next-token
                                 (assoc-in [:request :NextToken] next-token)))
          responses (conj responses response)]
      (if-let [next-token (:NextToken response)]
        (recur next-token responses)
        responses))))

(defn ensure-stack
  "returns an :op-map that can be invoked to ensure a stack exists with
  this description

  when op-map is invoked, the response has a :StackId key

  additionally returns a :computed key describing the difference
  detected between an existing stack and the computed values from the
  stack description, useful for later printing a summary of changes"
  [model cf stack-desc opts]
  (let [stack-desc (update stack-desc :params merge (:params opts))
        responses (unpaginate cf {:op :DescribeStacks})
        actual-stack (some (fn [stack]
                             (when (= (:StackName stack) (:name stack-desc))
                               stack))
                           (mapcat :Stacks responses))
        template-name (:template stack-desc)
        transform (get opts :transform identity)
        template (transform (templating/read-template model template-name))
        {:keys [:params] :as computed-params} (compute-params model actual-stack stack-desc template)
        {:keys [:template-body] :as computed-template} (compute-template cf actual-stack stack-desc template)
        op (if actual-stack
             :UpdateStack
             :CreateStack)
        build (build-info)
        [_ user] (:build-user build)]
    {:computed {:params computed-params
                :template computed-template}
     :op-map {:op op
              :request {:Capabilities ["CAPABILITY_NAMED_IAM"]
                        :StackName (:name stack-desc)
                        :Parameters params
                        :Tags [{:Key "git/commit"
                                :Value (:git-rev build)}
                               {:Key "git/branch"
                                :Value (:git-branch build)}
                               {:Key "build/user"
                                :Value user}]
                        :TemplateBody template-body}}}))

(defn color-status
  "https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-describing-stacks.html#w2ab1c15c15c17c11"
  [status]
  ((cond
     (string/ends-with? status "COMPLETE")
     style/success
     (string/ends-with? status "FAILED")
     style/error
     (string/ends-with? status "IN_PROGRESS")
     style/changed
     :else identity)
   status))

(defn block!
  "blocks until a stack-id has a completed event

  prints out the latest stack events every 3 seconds until it detects a
  completed resource status

  might miss events between calling update and starting to block"
  [cf stack-id]
  (loop [stop (Instant/now)]
    (let [stack (-> (invoke! cf {:op :DescribeStacks
                                 :request {:StackName stack-id}})
                    (:Stacks)
                    (first))
          events (-> (invoke! cf {:op :DescribeStackEvents
                                  :request {:StackName stack-id}})
                     (:StackEvents))]
      (doseq [event (reverse (filter (fn [event]
                                       (.isBefore stop (.toInstant (:Timestamp event))))
                                     events))]
        (println (.format DateTimeFormatter/ISO_INSTANT
                          (.toInstant (:Timestamp event)))
                 (:LogicalResourceId event)
                 (color-status (:ResourceStatus event))))
      (when-not (string/ends-with? (:StackStatus stack) "COMPLETE")
        (Thread/sleep 3000)
        (recur (.toInstant (last (sort (map :Timestamp events)))))))))

(defn alert-template!
  [computed]
  (println "\nChecking template..")
  (let [explanation (get-in computed [:template :explanation])]
    (print-template-diff explanation)))

(defn alert-params!
  "using the result from compute-params, print out the computed parameters
  and alert the user if there are missing parameters"
  [diff]
  (println "\nChecking parameters..")
  (let [{:keys [:param-desc]} (:params diff)
        hidden "<hidden>"
        param-desc (map (fn [desc]
                          (if (:no-echo? desc)
                            (select-keys (merge desc {:existing hidden :computed hidden :default hidden})
                                         (keys desc))
                            desc))
                        param-desc)
        max (apply max 0 (map (comp count :key) param-desc))]
    (doseq [param (sort-by :key param-desc)]
      (let [k (:key param)
            {:keys [:computed :default :existing]} param
            [category v] (cond (some? existing)
                               (if (and (some? computed)
                                        (not (= existing computed)))
                                 [:changed (str existing " ==> " computed)]
                                 [:previous existing])
                               (some? computed)
                               [:new computed]
                               (some? default)
                               [:default default]
                               :else [:missing nil])]
        (println (cond-> (format (str "  %-" max "s  %-9s  %s")
                                 k
                                 (name category)
                                 (if (some? v) v ""))
                   (#{:missing} category)
                   (-> style/error color/bold)
                   (#{:changed :new} category)
                   (-> style/changed color/bold)))))))

(defn ensure-stack!
  "ensures a stack exists with this description

  opts can be a map with:
  :block? - boolean whether to block for the operation to finish
  :params - additional params to send to the stack (like secrets)"
  [model stack-desc opts]
  (let [config (cond-> {:api :cloudformation}
                 (contains? stack-desc :region)
                 (assoc :region (:region stack-desc)))
        cf (aws/client config)
        {:keys [:computed :op-map]} (ensure-stack model cf stack-desc opts)
        changes? (or (get-in computed [:template :explanation])
                     (not (every? :UsePreviousValue (get-in computed [:params :params]))))
        apply-changes-prompt (if changes?
                               "\n\nApply changes?"
                               "\n\nNo changes detected, do you want to try to apply changes anyway?")]
    (println "\n\n" (style/wrap-with-emoji "🥞" (color/bold (:name stack-desc))))
    (println "\n\nStack description..")
    (doseq [k (concat (:order model) [:template])]
      (when-let [v (get stack-desc k)]
        (println " " (color/bold (name k)) v)))
    (alert-template! computed)
    (alert-params! computed)
    (if (prompt/yes-no apply-changes-prompt)
      (do (println (style/warn "Applying changes.."))
          (let [response (invoke! cf op-map)
                stack-id (:StackId response)]
            (if (:block? opts)
              (do (println "Waiting for operation to complete...")
                  (block! cf stack-id))
              (do (println (style/warn "NOT waiting for operation to complete."))
                  (println "Stack ID is" (style/id stack-id) ", check AWS Console for status.\n")))))
      (println (style/warn "NOT applying changes")))))

(defn ensure-stacks!
  [model stacks opts]
  (println "Using constants file" (style/path (:constants-file model)))
  (println "Using spec file" (style/path (:spec-file model)))

  (if (zero? (count stacks))
    (throw (Exception. "No stacks to ensure"))
    (do
      (println "\nEnsuring"
               (style/important (count stacks))
               (apply format "stack%s exist%s:"
                      (if (= (count stacks) 1) ["" "s"] ["s" ""]))
               (string/join " " (map :name stacks)))
      (doseq [stack stacks]
        (try
          (ensure-stack! model stack opts)
          (catch Exception e
            (println (style/error "Failed to ensure stack, caught exception: " e))
            (when-not (prompt/yes-no "\nErrors occurred when ensuring last stack, proceed with next stack?")
              (cli/exit! 1))))))))
