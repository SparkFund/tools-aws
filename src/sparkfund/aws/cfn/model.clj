(ns sparkfund.aws.cfn.model
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [clojure.math.combinatorics :as combo]
            [sparkfund.aws.cfn.ifns]))

(s/def ::stack
  (s/cat :template-kw #{:template}
         :template string?
         :per (s/? (s/cat :once-per-kw #{:once-per}
                          :bindings (s/coll-of any?)))
         :rest (s/* any?)))

(defn valid-instantiation?
  "returns true if the instantiation respects the structure"
  [structure instantiation]
  (every?
   (fn [[sym desc :as me]]
     (or (not (map? desc))
         (every?
          (fn [[k wanted-value]]
            (or (not (contains? instantiation k))
                (= (get instantiation k)
                   wanted-value)))
          (get desc (get instantiation sym)))))
   structure))

(defn valid-instantiations
  "for a once-per description, generates a collection of all valid
  instantiations according to the structure

  assumes the structure is small"
  [structure once-per]
  (let [vs (map (fn [axis]
                  (if (map? axis)
                    (do (assert (= (count axis) 1))
                        (let [[k vs] (first axis)]
                          (map (fn [v] {k v}) vs)))
                    (let [desc (get structure axis)]
                      (map
                       (fn [v] {axis v})
                       (cond (map? desc) (keys desc)
                             (set? desc) desc
                             :else [desc])))))
                once-per)]
    (into
     []
     (comp (map (fn [ms] (apply merge ms)))
           (filter (fn [instantiation] (valid-instantiation? structure instantiation))))
     (apply combo/cartesian-product vs))))

(defn substitute
  "given a stack description with keyword placeholders, substitutes the
  right values in according to the bindings"
  [bindings stack]
  (into
   {}
   (map (fn [[k v]]
          [k (walk/postwalk (fn [x]
                              (if (keyword? x)
                                (get bindings x)
                                x))
                            v)]))
   stack))

(defn sort-stacks
  [order stacks]
  (sort-by (apply juxt (concat order [:name :template])) stacks))

(defn print-stacks
  [model stacks]
  (let [{:keys [:order]} model
        ks (concat [:name] order [:template])]
    (pprint/print-table ks (sort-stacks order stacks))
    (println)))

(defn filter-stacks
  "filters stacks based on a description that is consistent with the
  `structure`"
  [stacks desc]
  (reduce
   (fn [stacks [arg val]]
     (if (some? val)
       (filter
        (fn [stack]
          (= (get stack arg) val))
        stacks)
       stacks))
   stacks
   desc))

(defn build-stacks
  [structure stacks-spec]
  (into
   []
   (comp (map (fn [stack-raw]
                (s/conform ::stack stack-raw)))
         (mapcat (fn [stack]
                   (map (fn [bindings]
                          (merge bindings
                                 {:template (:template stack)}
                                 (substitute bindings (apply hash-map (:rest stack)))))
                        (or (some->> (get-in stack [:per :bindings])
                                     (valid-instantiations structure))
                            [{}]))))
         (map (fn [stack]
                (update stack :name (fn [x]
                                      (if (coll? x)
                                        (clojure.string/join "-" x)
                                        x))))))
   stacks-spec))

(defn build-model
  [spec-file constants-file ifns-file]
  (let [spec (edn/read-string (slurp spec-file))]
    (merge (select-keys spec [:order :structure])
           {:constants (apply merge-with merge
                              (when (some? constants-file)
                                (edn/read-string (slurp constants-file))))
            :constants-file constants-file
            :ifns (when (some? ifns-file)
                    (into {}
                          (map (fn [[s f]]
                                 [s (eval f)]))
                          (edn/read-string (slurp ifns-file))))
            :ifns-file ifns-file
            :spec-file spec-file
            :stacks (build-stacks (:structure spec) (:stacks spec))})))
