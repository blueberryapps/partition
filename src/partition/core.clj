(ns partition.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [org.httpkit.client :as http]
            [clojure.tools.cli :as cli])
  (:gen-class)
  (:use clojure.test))

(def artifact-url-default-pattern "^.+?nightwatch_output$")

(def default-branch "master")

(def default-time 10000)

(def artifacts-url-template "https://circleci.com/api/v1/project/%user%/%project%/latest/artifacts?branch=%branch%&filter=successful&circle-token=%access-token%")

(defn tap
  [f v]
  (f v)
  v)

(deftest tap-test
  (is (= 1 @(tap #(vswap! % inc) (volatile! 0)))))

(defn log
  [minimal-verbosity-level actual-verbosity-level]
  (fn [message]
    (when (>= actual-verbosity-level minimal-verbosity-level)
      (println message))))

(deftest log-test
  (with-redefs [println identity]
    (is (nil? ((log 10 0) "whatever")))
    (is (= "whatever" ((log 0 10) "whatever")))))

(defn exit
  [status msg]
  (println msg)
  (System/exit status))

(defn partition-into
  [select-fn n col]
  (let [coll (reverse (sort-by select-fn col))]
    (reduce (fn [cubes val]
              (let [ordered-cubes (sort-by #(apply + (select-fn val) (map select-fn %1)) cubes)]
                (cons (conj (first ordered-cubes) val) (rest ordered-cubes))))
            (map vector (take n coll))
            (drop n coll))))

(deftest partition-into-test
  (testing "partitioning"
    (are [expected input count]
      (= expected (partition-into identity count input))
      [[1]] [1] 1
      [[7 1]] [7 1] 1
      [[7] [1]] [7 1] 2
      [[5 4 2] [8 2]] [4 5 2 8 2] 2
      [[2 2 1] [3 1 1] [5]] [5 2 3 2 1 1 1] 3))
  (testing "complex data structure, not just int"
    (is (= [[[:file 3]
             [:file 1]]
            [[:file 2]
             [:file 2]]]
           (partition-into second 2 [[:file 1]
                                     [:file 2]
                                     [:file 2]
                                     [:file 3]])))))

(defn parse-nightwatch-output
  [content]
  (->> content
       (re-seq #"(?s)\((.+?\.js)\).+?\(([\d]+)ms\)")
       (reduce (fn [acc [_ file time]]
                 (assoc acc (last (clojure.string/split file #"/")) (Integer/parseInt time)))
               {})))

(deftest parse-nightwatch-output-test
  (testing "empty input"
    (is (= {}
           (parse-nightwatch-output ""))))
  (testing "invalid input"
    (is (= {}
           (parse-nightwatch-output "abc"))))
  (testing "valid input"
    (is (= {"registrationValidation5.js" 9043
            "registrationValidation7.js" 6926}
           (parse-nightwatch-output "\n  User (/web/test/features1/registrationValidation5.js)\n    Should get error message\n\n      ✓ while register with no password (9043ms)\n\n  User (/web/test/features1/registrationValidation7.js)\n    Should get error message\n\n      ✓ while register with no values (6926ms)\n\n\n  25 passing (7m)\n\n")))))

(defn test-files
  [dir]
  (->> dir
       (io/file)
       (file-seq)
       (filter #(.isFile %))
       (reduce (fn [acc file]
                 (assoc acc (.getName file) default-time))
               {})))

(defn safe-merge
  [a b]
  (reduce (fn [acc [key value]]
            (if (get acc key)
              (assoc acc key value)
              acc))
          a b))

(deftest safe-merge-test
  (is (= {:a 10 :b 0 :c 15}
         (safe-merge {:a 0 :b 0 :c 0}
                     {:a 10 :c 15 :x 20}))))

(defn copy-files
  [in out]
  (fn [index cube]
    (doseq [row cube]
      (let [[file _] row
            source (str in "/" file)
            target (str out index "/" file)]
        (io/make-parents target)
        (io/copy (io/file source)
                 (io/file target))))))

(defn delete-files
  [all-files in]
  (fn [_ cube]
    (let [files-in-cube (into #{} (map first) cube)
          all-files (into [] (map first) all-files)
          files-to-delete (filter #(not (contains? files-in-cube %)) all-files)]
      (doseq [file files-to-delete]
        (io/delete-file (str in "/" file) :silently true)))))

(defn ok-response?
  [error status]
  (and (nil? error) (= status 200)))

(deftest ok-response?-test
  (is (= true
         (ok-response? nil 200)))
  (is (= false
         (ok-response? "err" 200)))
  (is (= false
         (ok-response? nil 500))))

(defn artifacts-url [options]
  (reduce (fn [url [key value]]
            (string/replace url (str "%" (name key) "%") (str value)))
          artifacts-url-template
          options))

(deftest artifacts-url-test
  (is (= artifacts-url-template
         (artifacts-url {})))
  (is (= "https://circleci.com/api/v1/project/abc/xyz/latest/artifacts?branch=master&filter=successful&circle-token=token"
         (artifacts-url {:user "abc"
                         :project "xyz"
                         :branch "master"
                         :access-token "token"})))
  (is (= artifacts-url-template
         (artifacts-url {:whatever 2}))))

(defn fetch-artifacts
  [log {:keys [access-token regexp] :as options}]
  (let [{:keys [status body error]} @(http/get (artifacts-url options) {:as :text})]
    (if (ok-response? error status)
      (let [futures (->> (clojure.edn/read-string body)
                         (map :url)
                         (filter #(re-matches (re-pattern regexp) %))
                         (map #(http/get (str % "?circle-token=" access-token) {:as :text}))
                         (doall))]
        (->> futures
             (map deref)
             (filter (fn [{:keys [status error]}]
                       (ok-response? error status)))
             (map :body)
             (reduce str "")))
      (do (log (str "Fetch artifacts error: (" status ") " error))
          ""))))

(deftest fetch-artifacts-test
  (testing "an error"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:error "An error"}))]
      (is (= ""
             (fetch-artifacts (fn [_]) {:regexp "[a-z]"})))))
  (testing "bad status"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 500}))]
      (is (= ""
             (fetch-artifacts (fn [_]) {:regexp "[a-z]"})))))
  (testing "ok, but no suitable arifact url"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 200 :body "[{:url \"whatever\"}]"}))]
      (is (= ""
             (fetch-artifacts (fn [_]) {:regexp "[a-z]"})))))
  (testing "ok, but arifact error"
    (with-redefs [http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:error "An error"}
                                                 {:status 200 :body "({:url \"a\"})"})))]
      (is (= ""
             (fetch-artifacts (fn [_]) {:regexp "[a-z]"})))))
  (testing "ok, but arifact invalid status"
    (with-redefs [http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:status 500}
                                                 {:status 200 :body "({:url \"a\"})"})))]
      (is (= ""
             (fetch-artifacts (fn [_]) {:regexp "[a-z]"})))))
  (testing "ok"
    (with-redefs [http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:status 200 :body "foo"}
                                                 "b?circle-token=" {:status 200 :body "bar"}
                                                 {:status 200 :body "({:url \"a\"} {:url \"b\"})"})))]
      (is (= "foobar"
             (fetch-artifacts (fn [_]) {:regexp "[a-z]"}))))))

(defn getenv
  [id]
  (System/getenv id))

(defn cli-options
  []
  [["-h" "--help" "Help"]
   ["-t" "--access-token ACCESS_TOKEN" "Access Token"]
   ["-u" "--user USER" "User"
    :default (getenv "CIRCLE_PROJECT_USERNAME")]
   ["-p" "--project PROJECT" "Project"
    :default (getenv "CIRCLE_PROJECT_REPONAME")]
   ["-b" "--branch BRANCH" "Branch"
    :default default-branch]
   ["-r" "--regexp REGEXP" "Artifact url pattern"
    :default artifact-url-default-pattern]
   ["-c" "--node-total NODE_TOTAL" "Count of nodes (workers)"
    :default (Integer/parseInt (or (getenv "CIRCLE_NODE_TOTAL") "1"))
    :parse-fn #(Integer/parseInt %)]
   ["-m" "--mode MODE" "Mode"
    :default "copy"
    :validate [#(contains? #{"copy" "delete"} %) "Must be one of copy or delete."]]
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]])

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args (cli-options))]
    (cond
      (:help options)
        (exit 1 summary)
      (and (> (:node-total options) 1)
           (not (:access-token options)))
        (exit 1 "Access token (--access-token option) is required when count of nodes (workers) > 1")
      (= (count arguments) 0)
        (exit 1 "Path to tests is missing")
      errors
        (exit 1 errors))
    (let [[in out] arguments
          test-files# (test-files in)]
      (->> (if (> (:node-total options) 1)
             (fetch-artifacts (log 0 (:verbosity options)) options)
             "")
           (parse-nightwatch-output)
           (safe-merge test-files#)
           (partition-into second (:node-total options))
           (tap (log 1 (:verbosity options)))
           (keep-indexed (if (= (:mode options) "copy")
                           (copy-files in (or out in))
                           (delete-files test-files# in)))
           (dorun)))))
