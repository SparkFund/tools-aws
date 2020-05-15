(ns sparkfund.aws.cfn.model-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer :all]
            [sparkfund.aws.cfn.model :as model]))

(deftest test-build-model
  (let [spec-file "test/data/spec.edn"
        constants-file "test/data/constants.edn"
        ifns-file "test/data/ifns.edn"
        model (model/build-model spec-file constants-file ifns-file)
        stacks (set (:stacks model))]
    (is (= spec-file (:spec-file model)))
    (is (= constants-file (:constants-file model)))
    (is (= ifns-file (:ifns-file model)))
    (is (= [:account :region :env :microservice] (get-in model [:order])))
    (is (= #{"db"} (get-in model [:structure :db])))
    (is (= {"ClojureVersion" "1.10.0442"} (get-in model [:constants true])))
    (is (= "t3.small" (get-in model [:constants :env "sandcastle" "EC2InstanceClass"])))
    (is (contains? stacks {:account "sandbox"
	                         :env "qa"
	                         :microservice "aaa"
	                         :template "templates/microservice.edn"
	                         :name "aaa-qa"
	                         :params {"Name" "aaa"}}))
    (is (= sparkfund.aws.cfn.ifns/iam-policy (get (:ifns model) 'iam-policy)))
    ))
