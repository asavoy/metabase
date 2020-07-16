(ns metabase.driver.sql-jdbc.sync-test
  (:require [clojure
             [string :as str]
             [test :refer :all]]
            [clojure.java.jdbc :as jdbc]
            [metabase
             [driver :as driver]
             [test :as mt]]
            [metabase.driver.sql-jdbc
             [connection :as sql-jdbc.conn]
             [sync :as sql-jdbc.sync]]
            [metabase.models.table :refer [Table]])
  (:import java.sql.ResultSet))

(defn- sql-jdbc-drivers-with-default-describe-database-impl
  "All SQL JDBC drivers that use the default SQL JDBC implementation of `describe-database`. (As far as I know, this is
  all of them.)"
  []
  (set
   (filter
    #(identical? (get-method driver/describe-database :sql-jdbc) (get-method driver/describe-database %))
    (descendants driver/hierarchy :sql-jdbc))))

(defn- describe-database-with-open-resultset-count
  "Just like `describe-database`, but instead of returning the database description returns the number of ResultSet
  objects the sync process left open. Make sure you wrap ResultSets with `with-open`! Otherwise some JDBC drivers like
  Oracle and Redshift will keep open cursors indefinitely."
  [driver db]
  (let [orig-result-set-seq jdbc/result-set-seq
        resultsets          (atom [])]
    ;; swap out `jdbc/result-set-seq` which is what ultimately gets called on result sets with a function that will
    ;; stash the ResultSet object in an atom so we can check whether its closed later
    (with-redefs [jdbc/result-set-seq (fn [^ResultSet rs & more]
                                        (swap! resultsets conj rs)
                                        (apply orig-result-set-seq rs more))]
      ;; taking advantage of the fact that `sql-jdbc.sync/describe-database` can accept JBDC connections instead of
      ;; databases; by doing this we can keep the connection open and check whether resultsets are still open before
      ;; they would normally get closed
      (jdbc/with-db-connection [conn (sql-jdbc.conn/db->pooled-connection-spec db)]
        (sql-jdbc.sync/describe-database driver conn)
        (reduce + (for [^ResultSet rs @resultsets]
                    (if (.isClosed rs) 0 1)))))))

(deftest dont-leak-resultsets-test
  (mt/test-drivers (sql-jdbc-drivers-with-default-describe-database-impl)
    (testing (str "make sure that running the sync process doesn't leak cursors because it's not closing the ResultSets. "
                  "See issues #4389, #6028, and #6467 (Oracle) and #7609 (Redshift)")
      (is (= 0
             (describe-database-with-open-resultset-count driver/*driver* (mt/db)))))))

(deftest database-types-fallback-test
  (mt/test-drivers (descendants driver/hierarchy :sql-jdbc)
    (let [org-result-set-seq jdbc/result-set-seq]
      (with-redefs [jdbc/result-set-seq (fn [& args]
                                          (map #(dissoc % :type_name) (apply org-result-set-seq args)))]
        (is (= #{{:name "longitude"   :base-type :type/Float}
                 {:name "category_id" :base-type :type/Integer}
                 {:name "price"       :base-type :type/Integer}
                 {:name "latitude"    :base-type :type/Float}
                 {:name "name"        :base-type :type/Text}
                 {:name "id"          :base-type :type/Integer}}
               (->> (sql-jdbc.sync/describe-table driver/*driver* (mt/id) (Table (mt/id :venues)))
                    :fields
                    (map (fn [{:keys [name base-type]}]
                           {:name      (str/lower-case name)
                            :base-type (if (isa? base-type :type/Integer) ; some DBs return the ID as BigInt
                                         :type/Integer
                                         base-type)}))
                    set)))))))
