(ns sparkfund.aws.cfn.templating-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [sparkfund.aws.cfn.model :as model]
            [sparkfund.aws.cfn.templating :as templating]))

(deftest test-read-template
  (let [spec-file "test/data/spec.edn"
        constants-file "test/data/constants.edn"
        ifns-file "test/data/ifns.edn"
        model (model/build-model spec-file constants-file ifns-file)
        actual (templating/read-template model "test/data/template.edn")]
    (testing "built-in expanded, keyword stringified"
      (is (= {"Ref" "Test2"}
             (get-in actual ["Resource" "Test1"]))))
    (testing "ifns.edn expanded"
      (is (= {"PolicyName" "Name"
	            "PolicyDocument" {"Version" "2012-10-17"
                                "Statement" ["Statement"]}}
             (get-in actual ["Resource" "Test3"]))))
    (testing "mappings from constants.edn"
      (is (= {"Accounts"
	            {"prod" {"Id" "123" "Name" "prod"}
	             "sandbox" {"Id" "456" "Name" "sandbox"}}}
             (get actual "Mappings"))))))
