(ns sparkfund.aws.cfn-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer :all]
            [sparkfund.aws.cfn :as cfn]
            [sparkfund.aws.cfn.model :as model]))

(def ex-model
  (model/build-model
   "test/data/spec.edn"
   "test/data/constants.edn"
   "test/data/ifns.edn"))

(deftest test-get-params
  (is (= {"ClojureVersion" "1.10.0442"}
         (cfn/get-params ex-model nil)))
  (is (= {"ClojureVersion" "1.10.0442"
          "Name" "aaa"}
         (cfn/get-params ex-model {:params {"Name" "aaa"}}))))

(deftest test-simplify-template-diff
  (let [a [1 2 3 4]
        e [1 2 4 4]
        path ["x"]]
    (is (= [{:path ["x" 2] :expected 4 :actual 3}]
           (cfn/simplify-template-diff a e path))))
  (let [a {"w" 3 "x" 4 "y" 5}
        e {"w" 3       "y" 6 "z" 7}
        path ["path"]]
    (is (= [{:path ["path" "z"] :expected 7 :actual nil}
	          {:path ["path" "x"] :expected nil :actual 4}
	          {:path ["path" "y"] :expected 6 :actual 5}]
           (cfn/simplify-template-diff a e path))))
  (testing "weird special case"
    (let [a {"key" "value"}
          e {"/key" "value"}
          path ["Resources" "...." "AWS::CloudFormation::Init" "config" "files"]]
      (is (= []
             (cfn/simplify-template-diff a e path))))))
