(ns fleetdb.core
  (:import (clojure.lang Numbers Sorted IDeref)
           (fleetdb Compare))
  (:require (clojure [set :as set])
            (clojure.contrib [core :as core]))
  (:use (fleetdb util)))

;; General ordering

(def- neg-inf :neg-inf)
(def- pos-inf :pos-inf)

(defn- record-compare [order]
  (assert order)
  (if (= 1 (count order))
    (let [[attr dir] (first order)]
      (cond
        (= dir :asc)
          #(Compare/compare (attr %1) (attr %2))
        (= dir :desc)
          #(Compare/compare (attr %2) (attr %1))
        :else
          (raise "invalid order " order)))
    (let [[[attr dir] & rorder] order
          rcompare              (record-compare rorder)]
      (cond
        (= dir :asc)
          #(let [c (Compare/compare (attr %1) (attr %2))]
             (if (zero? c) (rcompare %1 %2) c))
        (= dir :desc)
          #(let [c (Compare/compare (attr %2) (attr %1))]
             (if (zero? c) (rcompare %1 %2) c))
        :else
          (raise "invalid order " order)))))

(defn- attr-compare [order]
  (if (= 1 (count order))
    (let [[attr dir] (first order)]
      (cond
        (= dir :asc)
          #(Compare/compare %1 %2)
        (= dir :desc)
          #(Compare/compare %2 %1)
        :else
          (raise "invalid order " order)))
    (let [[[attr dir] & rorder] order
          rcompare (attr-compare rorder)
          next-fn  (if (= 1 (count rorder)) #(first (rest %)) rest)]
      (cond
        (= dir :asc)
          #(let [c (Compare/compare (first %1) (first %2))]
             (if (zero? c)
               (rcompare (next-fn %1) (next-fn %2))
               c))
        (= dir :desc)
          #(let [c (Compare/compare (first %1) (first %2))]
             (if (zero? c)
               (rcompare (next-fn %1) (next-fn %2))
                 c))
        :else
          (raise "invalid order " order)))))


;; Find planning

(defn- filter-plan [source where]
  (if where [:filter where source] source))

(defn- sort-plan [source order]
  (if order [:sort order source] source))

(defn- rmap-scan-plan [coll where order]
  (-> [:record-scan coll]
    (filter-plan where)
    (sort-plan order)))

(defn- index-order-prefix? [ispec order]
  (= (take (count order) ispec)
     order))

(def- flip-idir
  {:asc :desc :desc :asc})

(defn- flip-order [order]
  (map (fn [[attr dir]] [attr (flip-idir dir)]) order))

(defn- val-pad [left-v right-v ispec]
  (loop [left-vp left-v right-vp right-v rispec (drop (count left-v) ispec)]
    (if-let [[icattr icdir] (first rispec)]
      (if (= icdir :asc)
        (recur (conj left-vp neg-inf) (conj right-vp pos-inf) (rest rispec))
        (recur (conj left-vp pos-inf) (conj right-vp neg-inf) (rest rispec)))
      [left-vp right-vp])))

(defn- build-where-left [eq-left ineq-left other]
  (let [eq-conds   (map second (vals eq-left))
        ineq-conds (map second (vals ineq-left))
        conds      (concat eq-conds ineq-conds other)]
    (cond
      (empty? conds)      nil
      (= 1 (count conds)) (first conds)
      :multi-cond         (vec (cons :and conds)))))

(defn- conds-order-ipplan [ispec eq ineq other where order]
  (let [[rispec left-val left-inc right-val right-inc where-count where-left]
    (loop [rispec ispec left-val [] right-val [] where-count 0 eq-left eq]
      (if-not rispec
        [nil left-val true right-val true where-count (build-where-left eq-left ineq other)]
        (let [[icattr icdir] (first rispec)
              rrispec        (next rispec)]
          (if-let [[v _] (get eq-left icattr)]
            (recur rrispec (conj left-val v) (conj right-val v)
                   (inc where-count) (dissoc eq-left icattr))
            (if-let [[[low-v low-i high-v high-i] _] (get ineq icattr)]
              (let [w-left (build-where-left eq-left (dissoc ineq icattr) other)]
                (if (= icdir :asc)
                  [rrispec (conj left-val low-v)  low-i  (conj right-val high-v) high-i (inc where-count) w-left]
                  [rrispec (conj left-val high-v) high-i (conj right-val low-v)  low-i  (inc where-count) w-left]))
              [rispec left-val true right-val true where-count (build-where-left eq-left ineq other)])))))]
    (let [[order-left order-count sdir]
      (cond
        (empty? order)
          [nil 0 :left-right]
        (index-order-prefix? rispec order)
          [nil (count order) :left-right]
        (index-order-prefix? rispec (flip-order order))
          [nil (count order) :right-left]
        :else
          [order 0 :left-right])]
      (let [[left-val right-val] (val-pad left-val right-val ispec)]
        {:ispec       ispec
         :where-count where-count
         :order-count order-count
         :left-val    left-val
         :left-inc    left-inc
         :right-val   right-val
         :right-inc   right-inc
         :sdir        sdir
         :where-left  where-left
         :order-left  order-left}))))

(defn- flatten-where [where]
  (cond
    (not where)
      nil
    (= :and (first where))
      (rest where)
    :single-op
      (list where)))

(def- eq-op?
  #{:=})

(def- ineq-op?
  #{:< :<= :> :>= :>< :>=< :><= :>=<=})

(def- other-op?
  #{:!= :in :or})

(defn- cond-low-high [op v]
  (condp = op
    :<  [neg-inf true  v       false]
    :<= [neg-inf true  v       true ]
    :>  [v       false pos-inf true ]
    :>= [v       true  pos-inf true ]
    (let [[v1 v2] v]
      (condp = op
        :><   [v1 false v2 false]
        :>=<  [v1 true  v2 false]
        :><=  [v1 false v2 true ]
        :>=<= [v1 true  v2 true ]))))

(defn- partition-conds [conds]
  (reduce
    (fn [[eq ineq other] acond]
      (let [[cop cattr cval] acond]
        (cond
          (eq-op? cop)
            (if (contains? eq cattr)
              (raise "duplicate equality on " cattr)
              [(assoc eq cattr [cval acond]) ineq other])
          (ineq-op? cop)
            (if (contains? ineq cattr)
              (raise "duplicate inequality on " cattr)
              [eq (assoc ineq cattr [(cond-low-high cop cval) acond]) other])
          (other-op? cop)
            [eq ineq (conj other acond)]
          :else
            (raise "invalid where " acond))))
    [{} {} []]
    conds))

(defn- where-order-ipplans [ispecs where order]
  (let [[eq ineq other] (-> where flatten-where partition-conds)]
    (map #(conds-order-ipplan % eq ineq other where order) ispecs)))

(defn- ipplan-compare [a b]
  (compare [(:where-count a) (:order-count a)] [(:where-count b) (:order-count b)]))

(defn- ipplan-useful? [ipplan]
  (pos? (+ (:where-count ipplan) (:order-count ipplan))))

(defn- ipplan-plan [coll {:keys [ispec sdir left-val left-inc
                                 right-val right-inc where-left order-left]}]
  (let [left-val-t  (if (= (count left-val)  1) (first left-val)  left-val)
        right-val-t (if (= (count right-val) 1) (first right-val) right-val)]
    (-> (if (= left-val-t right-val-t)
          [:index-lookup [coll ispec left-val-t]]
          [:index-seq    [coll ispec sdir left-val-t left-inc right-val-t right-inc]])
      (filter-plan where-left)
      (sort-plan   order-left))))

(defn- where-order-plan* [coll ispecs where order]
  (let [ipplans (where-order-ipplans ispecs where order)
        ipplan  (high ipplan-compare ipplans)]
    (if (and ipplan (ipplan-useful? ipplan))
      (ipplan-plan coll ipplan)
      (rmap-scan-plan coll where order))))

(defn- where-order-plan [coll ispecs where order]
  (cond
    (and (= (get where 0) :=) (= (get where 1) :id))
      [:record-lookup [coll (get where 2)]]

    (and (= (get where 0) :in) (= (get where 1) :id))
      (-> [:record-multilookup [coll (get where 2)]]
        (sort-plan order))

    (= (get where 0) :or)
      [:union order (map #(where-order-plan coll ispecs % order) (next where))]

    :else
      (where-order-plan* coll ispecs where order)))

(defn- offset-plan [source offset]
  (if offset [:offset offset source] source))

(defn- limit-plan [source limit]
  (if limit [:limit limit source] source))

(defn- only-plan [source only]
  (if only [:only only source] source))

(defn- find-plan [coll ispecs where order offset limit only]
  (-> (where-order-plan coll ispecs where order)
   (offset-plan offset)
   (limit-plan  limit)
   (only-plan   only)))


;; Find execution

(def- conj-op-fns
  {:and and? :or or?})

(def- sing-op-fns
  {:=  #(Compare/eq  %1 %2)
   :!= #(Compare/neq %1 %2)
   :<  #(Compare/lt  %1 %2)
   :<= #(Compare/lte %1 %2)
   :>  #(Compare/gt  %1 %2)
   :>= #(Compare/gte %1 %2)})

(def- doub-op-fns
  {:><   [(sing-op-fns :> ) (sing-op-fns :< )]
   :>=<  [(sing-op-fns :>=) (sing-op-fns :< )]
   :><=  [(sing-op-fns :> ) (sing-op-fns :<=)]
   :>=<= [(sing-op-fns :>=) (sing-op-fns :<=)]})

(defn- where-pred [[op & wrest]]
  (cond
    (conj-op-fns op)
      (let [subpreds (map #(where-pred %) wrest)
            conj-op-fn  (conj-op-fns op)]
        (fn [record]
          (conj-op-fn (map #(% record) subpreds))))
    (sing-op-fns op)
      (let [[attr aval] wrest
            sing-op-fn  (sing-op-fns op)]
        (fn [record]
          (sing-op-fn (attr record) aval)))
    (doub-op-fns op)
      (let [[attr [aval1 aval2]] wrest
            [doub-op-fn1 doub-op-fn2]      (doub-op-fns op)]
        (fn [record]
          (let [record-aval (attr record)]
            (and (doub-op-fn1 record-aval aval1)
                 (doub-op-fn2 record-aval aval2)))))
    (= op :in)
      (let [[attr aval-vec] wrest
            aval-set        (set aval-vec)]
        (fn [record]
          (contains? aval-set (attr record))))
    :else
      (raise "where op " op " not recognized")))

(defmulti- exec-plan (fn [db [plan-type _]] plan-type))

(defmethod exec-plan :filter [db [_ where source]]
  (filter (where-pred where) (exec-plan db source)))

(defmethod exec-plan :sort [db [_ order source]]
  (sort (record-compare order) (exec-plan db source)))

(defmethod exec-plan :offset [db [_ offset source]]
  (drop offset (exec-plan db source)))

(defmethod exec-plan :limit [db [_ limit source]]
  (take limit (exec-plan db source)))

(defmethod exec-plan :only [db [_ only source]]
  (cond
    (vector? only)
      (map (fn [r] (vec-map #(% r) only)) (exec-plan db source))
    (keyword? only)
      (map only (exec-plan db source))
    :other
      (raise "Unrecognized :only value: " only)))

(defmethod exec-plan :union [db [_ order sources]]
  (uniq
    (sort (record-compare (or order [[:id :asc]]))
      (apply concat (map #(exec-plan db %) sources)))))

(defmethod exec-plan :record-lookup [db [_ [coll id]]]
  (if-let [record (get-in db [:rmaps coll id])]
    (list record)))

(defmethod exec-plan :record-multilookup [db [_ [coll ids]]]
  (if-let [rmap (get-in db [:rmaps coll])]
    (compact (map #(rmap %) ids))))

(defmethod exec-plan :record-scan [db [_ coll]]
  (vals (get-in db [:rmaps coll])))

(defn- indexed-flatten1 [indexed]
  (cond (nil? indexed) nil
        (set? indexed) indexed
        :single        (list indexed)))

(defn- indexed-flatten [indexeds]
  (lazy-seq
    (when-let [iseq (seq indexeds)]
      (let [f (first iseq) r (rest iseq)]
        (cond
          (nil? f) (indexed-flatten r)
          (set? f) (concat f (indexed-flatten r))
          :single  (cons f (indexed-flatten r)))))))

(defmethod exec-plan :index-lookup [db [_ [coll ispec val]]]
  (indexed-flatten1 (get-in db [:imaps coll ispec val])))

(defmethod exec-plan :index-seq
  [db [_ [coll ispec sdir left-val left-inc right-val right-inc]]]
    (let [#^Sorted index (get-in db [:imaps coll ispec])
          indexeds
      (if (= sdir :left-right)
        (let [base    (.seqFrom index left-val true)
              base-l  (if (or left-inc (!= (key (first base)) left-val))
                        base
                        (rest base))
              base-lr (if right-inc
                        (take-while #(Compare/lte (key %) right-val) base-l)
                        (take-while #(Compare/lt  (key %) right-val) base-l))]
          (vals base-lr))
        (let [base    (.seqFrom index right-val false)
              base-r  (if (or right-inc (!= (key (first base)) right-val))
                        base
                        (rest base))
              base-rl (if left-inc
                        (take-while #(Compare/gte (key %) left-val) base-r)
                        (take-while #(Compare/gt  (key %) left-val) base-r))]
          (vals base-rl)))]
      (indexed-flatten indexeds)))

(defn- coll-ispecs [db coll]
  (keys (get-in db [:imaps coll])))

(defn- find-records [db coll {:keys [where order offset limit only] :as opts}]
  (assert (or (nil? opts) (map? opts)))
  (exec-plan db
    (find-plan coll (coll-ispecs db coll) where order offset limit only)))


;; RMap and IMap manipulation

(defn- rmap-insert [rmap record]
  (let [id (:id record)]
    (if-not id
      (raise "Record does not have an :id: " record))
    (if (contains? rmap id)
      (raise "Duplicated id: " id))
    (assoc rmap (:id record) record)))

(defn- rmap-update [rmap old-record new-record]
  (assoc rmap (:id old-record) new-record))

(defn- rmap-delete [rmap old-record]
  (dissoc rmap (:id old-record)))

(defn- ispec-on-fn [ispec]
  (let [attrs (map first ispec)]
    (cond
      (empty? attrs)      (raise "empty ispec: " ispec)
      (= 1 (count attrs)) (first attrs)
      :multi-attr         #(vec (map % attrs)))))

(defn- index-insert [index on-fn record]
  (update index (on-fn record)
    (fn [indexed]
      (cond
        (nil? indexed) record
        (set? indexed) (conj indexed record)
        :single-record (hash-set indexed record)))))

(defn- index-delete [index on-fn record]
  (let [aval    (on-fn record)
        indexed (get index aval)]
    (update index aval
      (fn [indexed]
        (cond
          (nil? indexed) (raise "missing record")
          (set? indexed) (do
                           (assert (contains? indexed record))
                           (disj indexed record))
          :single-record (do (assert (= indexed record))
                           nil))))))

(defn- index-build [records ispec]
  (let [on-fn (ispec-on-fn ispec)]
    (reduce
      (fn [i r] (index-insert i on-fn r))
      (sorted-map-by (attr-compare ispec))
      records)))

(defn- imap-apply [imap apply-fn]
  (mash (fn [ispec index] (apply-fn (ispec-on-fn ispec) index)) imap))

(defn- imap-insert [imap record]
  (imap-apply imap
    (fn [on-fn index]
      (index-insert index on-fn record))))

(defn- imap-update [imap old-record new-record]
  (imap-apply imap
    (fn [on-fn index]
      (-> index
        (index-delete on-fn old-record)
        (index-insert on-fn new-record)))))

(defn- imap-delete [imap record]
  (imap-apply imap
    (fn [on-fn index] (index-delete index on-fn record))))


;; Query implementations

(defmulti query (fn [db [query-type opts]] query-type))

(defmethod query :default [_ [query-type]]
  (raise "Invalid query type: " query-type))

(defmethod query :select [db [_ coll opts]]
  (find-records db coll opts))

(defmethod query :get [db [_ coll id-s]]
  (if-let [rmap (get-in db [:rmaps coll])]
    (if (vector? id-s)
      (compact (map #(rmap %) id-s))
      (rmap id-s))))

(defmethod query :count [db [_ coll opts]]
  (count (find-records db coll opts)))

(defn- db-apply [db coll records apply-fn]
  (let [{old-rmaps :rmaps old-imaps :imaps} db
        old-rmap   (coll old-rmaps)
        old-imap   (coll old-imaps)
        [new-rmap new-imap] (reduce apply-fn [old-rmap old-imap] records)]
    [(-> db
       (assoc-in [:rmaps coll] new-rmap)
       (assoc-in [:imaps coll] new-imap))
     (count records)]))

(defmethod query :insert [db [_ coll record-s]]
  (db-apply db coll (if (map? record-s) (list record-s) record-s)
    (fn [[int-rmap int-imap] record]
      [(rmap-insert int-rmap record)
       (imap-insert int-imap record)])))

(defmethod query :update [db [_ coll with opts]]
  (db-apply db coll (find-records db coll opts)
    (fn [[int-rmap int-imap] old-record]
      (let [new-record (merge-compact old-record with)]
        [(rmap-update int-rmap old-record new-record)
         (imap-update int-imap old-record new-record)]))))

(defmethod query :delete [db [_ coll opts]]
  (db-apply db coll (find-records db coll opts)
    (fn [[int-rmap int-imap] old-record]
      [(rmap-delete int-rmap old-record)
       (imap-delete int-imap old-record)])))

(defmethod query :explain [db [_ [query-type coll opts]]]
  (assert (= query-type :select))
  (let [{:keys [where order offset limit only]} opts]
    (find-plan coll (coll-ispecs db coll) where order offset limit only)))

(defmethod query :list-collections [db _]
  (uniq (sort
    (map first
      (filter #(not (empty? (second %)))
              (concat (:rmaps db) (:imaps db)))))))

(defmethod query :create-index [db [_ coll ispec]]
  (if (get-in db [:imaps coll ispec])
    [db 0]
    (let [records (vals (get-in db [:rmaps coll]))
          index   (index-build records ispec)]
      [(assoc-in db [:imaps coll ispec] index) 1])))

(defmethod query :drop-index [db [_ coll ispec]]
  (if-not (get-in db [:imaps coll ispec])
    [db 0]
    [(core/dissoc-in db [:imaps coll ispec]) 1]))

(defmethod query :list-indexes [db [_ coll]]
  (keys (get-in db [:imaps coll])))

(defmethod query :multi-read [db [_ queries]]
  (vec (map #(query db %) queries)))

(defmethod query :multi-write [db [_ queries]]
  (reduce
    (fn [[int-db int-results] q]
      (let [[aug-db result] (query int-db q)]
        [aug-db (conj int-results result)]))
    [db []]
    queries))

(defmethod query :checked-write [db [_ check expected write]]
  (let [actual (query db check)]
    (if (= actual expected)
      (let [[new-db result] (query db write)]
        [new-db [true result]])
      [db [false actual]])))

(defn init []
  {:rmaps {}
   :imaps {}})
