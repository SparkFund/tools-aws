(ns sparkfund.aws.cfn.ifns
  "Shortcuts for the various CloudFormation intrinsic functions
   https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference.html"
  (:refer-clojure :only [binding defn fn keyword? let mapv name pr-str vec *print-namespace-maps* *print-length* *print-level*])
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]))

;;referencing other values
(defn ref [r] {"Ref" r})
(defn attr [r a] {"Fn::GetAtt" [r a]})
(defn format
  ([s] {"Fn::Sub" s})
  ([s vs] {"Fn::Sub" [s vs]}))
(defn import-value [s] {"Fn::ImportValue" s})
(defn mapping-value [name k1 k2] {"Fn::FindInMap" [name k1 k2]})

;;manipulation
(defn split [delim s] {"Fn::Split" [delim s]})
(defn join
  ([coll] (join "" coll))
  ([delim coll] {"Fn::Join" [delim coll]}))
(defn str [& ss] (join (vec ss)))
(defn nth [n coll] {"Fn::Select" [n coll]})
(defn first [coll] (nth 0 coll))
(defn second [coll] (nth 1 coll))

;;string encoding
(defn base64-encode [s] {"Fn::Base64" s})
(defn json-encode [v] (json/write-str (walk/stringify-keys v)))
(defn edn-encode [v]
  (binding [*print-namespace-maps* false
            *print-length* nil
            *print-level* nil]
    (pr-str v)))

;;conditional operators
(defn and [& conds] {"Fn::And" (vec conds)})
(defn or [& conds] {"Fn::Or" (vec conds)})
(defn = [x y] {"Fn::Equals" [x y]})
(defn not [v] {"Fn::Not" [v]})
(defn not= [x y] (not (= x y)))

;;conditional tests
(def no-value (ref "AWS::NoValue"))
(defn if? [c y n] {"Fn::If" [c y n]})
(defn when [c y] (if? c y no-value))
(defn when-not [c y] (if? c no-value y))

;;misc
(defn azs
  ([] (azs (ref "AWS::Region")))
  ([region] {"Fn::GetAZs" region}))

(defn tags [m]
  (mapv (fn [[k v]]
          {"Key"   (if (keyword? k) (name k) k)
           "Value" v})
        m))

(def built-in
  "Shortcuts for the various CloudFormation intrinsic functions
  https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference.html"
  {'ref           ref
   'attr          attr
   'format        format
   'import-value  import-value
   'mapping-value mapping-value
   'split         split
   'join          join
   'str           str
   'nth           nth
   'first         first
   'second        second
   'base64-encode base64-encode
   'json-encode   json-encode
   'edn-encode    edn-encode
   'and           and
   'or            or
   '=             =
   'not           not
   'not=          not=
   'if            if?
   'when          when
   'when-not      when-not
   'tags          tags
   })


;;;; the following are not added by default, but you can still
;;;; reference them in an ifns.edn file if you want them


(defn iam-policy
  "Cuts out some of the boilerplate when making an IAM policy."
  [policy-name statements]
  {"PolicyName" policy-name
   "PolicyDocument" {"Version" "2012-10-17"
                     "Statement" statements}})

(defn assume-roles-policy
  "Cuts out some of the boilerplate when making an IAM policy that
  grants the ability to assume one or more roles."
  [policy-name role-arns]
  (let [statements [{"Effect" "Allow"
                     "Action" "sts:AssumeRole"
                     "Resource" role-arns}]]
    (iam-policy policy-name statements)))
