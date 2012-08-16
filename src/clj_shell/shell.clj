(ns
  clj-shell.shell
  (:use [clojure.java.io :only (as-file copy input-stream)])
  (:require clojure.java.shell)
  (:import (java.io OutputStreamWriter)))

(def ^{:private true} parse-args
     (ns-resolve 'clojure.java.shell 'parse-args))
(def ^{:private true} as-env-strings
     (ns-resolve 'clojure.java.shell 'as-env-strings))
(def ^{:private true} stream-to-enc
     (ns-resolve 'clojure.java.shell 'stream-to-enc))
(def ^{:private true} stream-to-string
     (ns-resolve 'clojure.java.shell 'stream-to-string))

(defn sh* [& args]
  (let [[cmd opts] (parse-args args)
        proc (.exec (Runtime/getRuntime) 
		    ^"[Ljava.lang.String;" (into-array cmd) 
		    (as-env-strings (:env opts))
		    (as-file (:dir opts)))
        {:keys [in in-enc out-enc out-fn err-fn]} opts]
    (if in
      (future
        (cond
         (string? in)
         (with-open [osw (OutputStreamWriter. (.getOutputStream proc) ^String in-enc)]
           (.write osw ^String in))
         (instance? (class (byte-array 0)) in)   
         (with-open [os (.getOutputStream proc)]
           (.write os ^"[B" in))
         :else
         (with-open [os (.getOutputStream proc)
                     is (input-stream in)]
           (copy is os))))
      (.close (.getOutputStream proc)))
    {:proc proc
     :result (future
               (with-open [stdout (.getInputStream proc)
                           stderr (.getErrorStream proc)]
                 (let [out (future (if (fn? out-fn)
                                     (out-fn stdout)
                                     (stream-to-enc stdout out-enc)))
                       err (future (if (fn? err-fn)
                                     (err-fn stderr)
                                     (stream-to-string stderr)))
                       exit (.waitFor proc)]
                   {:exit exit
                    :out @out
                    :err @err})))}))


(defn sh
  "Passes the given strings to Runtime.exec() to launch a sub-process.

  Options are

  :in      may be given followed by a String or byte array specifying input
           to be fed to the sub-process's stdin. If :in isn't a String or
           a byte array the value is passed to the clojure.java.io/input-stream
           function and directly copied to the sub-process's stdin.
  :in-enc  option may be given followed by a String, used as a character
           encoding name (for example \"UTF-8\" or \"ISO-8859-1\") to
           convert the input string specified by the :in option to the
           sub-process's stdin.  Defaults to UTF-8.
           If the :in option provides a byte array, then the bytes are passed
           unencoded, and this option is ignored.
  :out-enc option may be given followed by :bytes or a String. If a
           String is given, it will be used as a character encoding
           name (for example \"UTF-8\" or \"ISO-8859-1\") to convert
           the sub-process's stdout to a String which is returned.
           If :bytes is given, the sub-process's stdout will be stored
           in a byte array and returned.  Defaults to UTF-8.
  :env     override the process env with a map (or the underlying Java
           String[] if you are a masochist).
  :dir     override the process dir with a String or java.io.File.
  :out-fn  a custom one argument function to process the stdout
           java.io.InputStream of the java.lang.Process. The stream
           obtains data piped from the standard output stream (stdout) of
           the process represented by the underlying Process object.
           You don't need to take care of closing the java.io.InputStream.
  :err-fn  a custom one argument function to process the stderr
           java.io.InputStream of the java.lang.Process. The stream
           obtains data piped from the error output stream (stderr) of
           the process represented by the underlying Process object.
           You don't need to take care of closing the java.io.InputStream.

  You can bind :env or :dir for multiple operations using with-sh-env
  and with-sh-dir.

  sh returns a map of
    :exit => sub-process's exit code
    :out  => sub-process's stdout (as byte[] or String)
    :err  => sub-process's stderr (String via platform default encoding)"
  [& args]
  @(:result (apply sh* args)))
