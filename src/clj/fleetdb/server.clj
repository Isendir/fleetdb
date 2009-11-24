(ns fleetdb.server
  (:require (fleetdb [embedded :as embedded] [io :as io] [exec :as exec])
            (clj-stacktrace [repl :as stacktrace]))
  (:import  (java.net ServerSocket Socket)
            (java.io PushbackReader BufferedReader InputStreamReader
                     PrintWriter    BufferedWriter OutputStreamWriter
                     DataInputStream  BufferedInputStream
                     DataOutputStream BufferedOutputStream )))

(defn- safe-query [dba query]
  (try
    (let [result (embedded/query dba query)]
      (if (coll? result) (vec result) result))
    (catch Exception e e)))

(defn- text-read-query [#^PushbackReader in eof-val]
  (try
    (read in false eof-val)
    (catch Exception e e)))

(defn- text-write-exception [#^PrintWriter out e]
  (stacktrace/pst-on out false e)
  (.println out)
  (.flush out))

(defn- text-write-result [#^PrintWriter out result]
  (.println out (prn-str result))
  (.flush out))

(defn- text-handler [dba #^Socket socket]
  (try
    (with-open [socket socket
                out    (PrintWriter.    (BufferedWriter. (OutputStreamWriter. (.getOutputStream socket))))
                in     (PushbackReader. (BufferedReader. (InputStreamReader.  (.getInputStream  socket))))]
      (loop []
        (let [read-result (text-read-query in io/eof)]
          (when-not (identical? io/eof read-result)
            (if (instance? Exception read-result)
              (text-write-exception out read-result)
              (let [query-result (safe-query dba read-result)]
                (if (instance? Exception query-result)
                  (text-write-exception out query-result)
                  (text-write-result out query-result))))
            (recur)))))
    (catch Exception e
      (stacktrace/pst-on System/err false e)
      (.println System/err))))

(defn- binary-read-query [#^DataInputStream in eof-val]
  (try
    (io/dis-read in eof-val)
    (catch Exception e e)))

(defn- binary-write-exception [#^DataOutputStream out e]
  (io/dos-write [1 (stacktrace/pst-str e)]))

(defn- binary-write-result [#^DataOutputStream out result]
  (io/dos-write out [0 result]))

(defn- binary-handler [dba #^Socket socket]
  (try
    (with-open [socket socket
                out    (DataOutputStream. (BufferedOutputStream. (.getOutputStream socket)))
                in     (DataInputStream.  (BufferedInputStream.  (.getInputStream  socket)))]
      (loop []
        (let [read-result (binary-read-query in io/eof)]
          (when-not (identical? io/eof read-result)
            (if (instance? Exception read-result)
              (binary-write-exception out read-result)
              (let [query-result (safe-query dba read-result)]
                (if (instance? Exception query-result)
                  (binary-write-exception out query-result)
                  (binary-write-result out query-result))))
            (recur)))))
    (catch Exception e
      (stacktrace/pst-on System/err false e)
      (.println System/err))))

(defn run [read-path write-path port binary]
  (let [text-ss (ServerSocket. port)
        dba     (embedded/init read-path write-path)
        pool    (exec/init-pool 100)
        handler (if binary binary-handler text-handler)]
    (println "FleetDB: serving port" port)
    (loop []
      (let [socket (.accept text-ss)]
        (exec/submit pool #(handler dba socket)))
      (recur))))

(use 'clojure.contrib.shell-out)
(sh "rm" "-f" "/Users/mmcgrana/Desktop/log")
(run nil "/Users/mmcgrana/Desktop/log" 4444 true)