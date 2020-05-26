## tools-aws

Using infrastructure-as-data to demystify CloudFormation operations.

Inspired by Terraform and driven by a need for guardrails,
friendliness, and interactivity, this project outlines how Sparkfund
does CloudFormation operations.

Although the source code for this library is now open source, it is
offered as a demonstration, not as a promise of continued development
or support.

### Usage

#### Models

```clj
(def model (model/build-model spec-file constants-file ifns-file))
model
;; => {:stacks ... :constants ...}
```

Example `spec.edn` file:
```clj
{:structure
 {:account #{"sandbox" "prod"}
  :env {"sandcastle" {:account "sandbox"}
        "qa" {:account "sandbox"}
        "prod" {:account "prod"}}
  :microservice #{"aaa" "bbb"}
  :db #{"db"}}

 :order
 [:account :env :microservice]

 :stacks
 [[:template "templates/account.edn"
   :once-per [:account]
   :name ["account"]]

  [:template "templates/environment.edn"
   :once-per [:account :env]
   :name ["environment"]]

  [:template "templates/database.edn"
   :once-per [:account :env :db]
   :name [:env :db]]

  [:template "templates/microservice.edn"
   :once-per [:account :env :microservice]
   :name [:microservice :env]
   :params {"Name" :microservice}]
  ]}
```

The only required keyword is `:account`, as that is special-cased in
several places.

Example `constants.edn` file:
```clj
[{true
  {"ClojureVersion" "1.10.0442"}}
 {:account
  {"prod"
   {:id "123"
    :name "prod"}
   "sandbox"
   {:id "456"
    :name "sandbox"}}}
 {:env
  {"sandcastle"
   {"Env" "sandcastle"
    "EC2InstanceClass" "t3.small"
    "SlackAlarmChannel" "#dev-null"}
   "qa"
   {"Env" "qa"
    "EC2InstanceClass" "t3.medium"
    "SlackAlarmChannel" "#dev-staging-alerts"}
   "prod"
   {"Env" "prod"
    "EC2InstanceClass" "t3.large"
    "SlackAlarmChannel" "#dev-prod-alerts"}}}
 ]
```

The `[:account :id]` and `[:account :name]` paths are optional to
provide shorthands for ifns, where `:id` is the actual Account ID and
`:name` is a consistent string shorthand for the account.

Example `ifns.edn` file:
```clj
{iam-policy sparkfund.aws.cfn.ifns/iam-policy
 assume-roles-policy sparkfund.aws.cfn.ifns/assume-roles-policy
 }
```

The map values should be `eval`-able in the environment where the
model is built.

See [`sparkfund.aws.cfn.ifns`](./src/sparkfund/aws/cfn/ifns.clj) for
the full list of built-in ifn support.

For example, in your EDN templates you can write:
```clj
(join "," ["abc" "xyz"])
;; =>
{"Fn::Join" ["," ["abc" "xyz"]]}
;; =>
;; JSON
```

### Ensure Stack

Once you've defined the model, you can use `model/filter-stacks`,
`model/print-stacks`, `cfn/ensure-stacks!` and other
[`sparkfund.cli`](https://github.com/SparkFund/tools-cli) helpers in
any command-line scripts that need to update CloudFormation stacks.

For example:

```clj
(let [filters {:name "aaa-sandcastle"}
      stacks (model/filter-stacks (:stacks model) filters)]
  (println "Found" (count stacks) "matching" (if (= 1 (count stacks)) "stack" "stacks"))
  (model/print-stacks shared/model stacks)
  (do (cfn/ensure-stacks!
       shared/model
       stacks
       {:block? true
        :params {"Param3" 1}})
      (cli/exit! 0)))
```

This expression might output the following interactive dialogue:

```
Found 1 matching stack

|          :name | :account |       :env | :microservice |                 :template |
|----------------+----------+------------+-------------------------------------------|
| aaa-sandcastle |  sandbox | sandcastle |           aaa |          microservice.edn |

Using constants file constants.edn
Using spec file spec.edn

Ensuring 1 stack exists: aaa-sandcastle


 🥞 aaa-sandcastle 🥞


Stack description..
  account sandbox
  env sandcastle
  microservice aaa
  template microservice.edn

Checking template..
 Path:
   CLOUD: {"Resources" {"Ex" "Value1"}}
   LOCAL: {"Resources" {"Ex" "Value2"}}

Checking parameters..
  Param1  previous   1000
  Param2  default    4
  Param3  changed    0 => 1

Apply changes? [y/n] y
```

Followed by a summary of the CloudFormation events.


### License

Copyright © Sparkfund 2020

Distributed under the Apache License, Version 2.0. See LICENSE for details.
