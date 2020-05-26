(ns sparkfund.aws.cfn.templating
  (:require [clojure.walk :as walk]
            [sparkfund.aws.cfn.ifns :as ifns]
            [sparkfund.aws.cfn.model :as model]))

(defn build-mappings
  "Builds mappings from the constants, including an Accounts key with
   ids and names for the account aliases"
  [constants]
  {"Accounts"
   (into {}
         (map (fn [[k v]]
                (let [{:keys [id name]} v]
                  [k {"Id" id
                      "Name" name}])))
         (get constants :account))})

;;super useful helpers for generating little template fragments
(defn account-id
  "Digs out an account by shortname, using the mapping provided to all
  templates via `build-mappings`.  The shortname corresponds to the
  keys of the :account map in `constants.edn`.  This yields an actual
  account ID inlined into the template, which should make the template
  less verbose."
  [mappings account-shortname]
  (let [accounts (get mappings "Accounts")
        id (get-in accounts [account-shortname "Id"])]
    (if-not (empty? id)
      id
      (throw (ex-info (str "Unable to resolve invalid account-shortname: " account-shortname)
                      {:requested account-shortname
                       :valid (set (keys accounts))})))))

(defn role-arn
  "Computes a handy role ARN based on account shortname and role name.
  This yields an actual ARN including account ID, inlined into the
  template, instead of a bunch of Fn::GetInMaps, which tend to get too
  verbose and unreadable."
  [mappings account-shortname role-name]
  (str "arn:aws:iam::" (account-id mappings account-shortname) ":role/" role-name))

(defn build-expansions
  "Creates mapping-aware expansions."
  [ifns mappings]
  (merge {'account-id (partial account-id mappings)
          'role-arn (partial role-arn mappings)}
         ifns/built-in
         ifns))

(defn expand-one
  "Allows more convenient expression of CloudFormation's intrinsic
  functions in EDN templates"
  [form expansions]
  (if (sequential? form)
    (if-let [fn (get expansions (first form))]
      (apply fn (rest form))
      (if (symbol? (first form))
        (throw (ex-info (str "undefined ifn shorthand: " (first form)) {:form form}))
        form))
    form))

(defn expand-all
  [form expansions]
  (walk/postwalk #(expand-one % expansions) form))

(defn read-template
  [model template]
  (let [mappings (build-mappings (:constants model))
        expansions (build-expansions (:ifns model) mappings)]
    (some-> template
      slurp
      clojure.edn/read-string
      (expand-all expansions)
      clojure.walk/stringify-keys
      (update "Mappings" merge mappings))))
