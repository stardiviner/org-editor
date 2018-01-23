(ns com.gfredericks.org-editor
  (:require
   [clojure.java.io    :as io]
   [clojure.spec.alpha :as s]
   [clojure.string     :as string]))

(s/def ::file (s/keys :req [::prelude ::sections]))
(s/def ::line (s/and string? #(not (re-find #"[\n\r]" %))))
(s/def ::lines (s/coll-of ::line :kind sequential?))
(s/def ::prelude ::lines)
(s/def ::sections (s/coll-of ::section :kind sequential?))
(s/def ::section (s/keys :req [::header ::prelude ::sections]))
(def header-line-regex #"(\*+) (.*)")
(s/def ::header (s/and string? #(re-matches header-line-regex %)))

(s/fdef parse-file
        :args (s/cat :reader #(instance? java.io.Reader %))
        :ret ::file)
(defn parse-file
  [reader]
  (with-open [br (java.io.BufferedReader. reader)]
    (let [lines (line-seq br)

          [prelude sections]
          ((fn parse-prelude-and-sections [lines min-level]
             (let [[prelude more-lines] (split-with #(not (re-matches header-line-regex %)) lines)]
               (loop [sections []
                      lines    more-lines]
                 (if (empty? lines)
                   [prelude sections []]
                   (let [[line & more-lines] lines
                         [whole-line level] (re-matches header-line-regex line)]
                     (if (< (count level) min-level)
                       [prelude sections lines]
                       (let [[prelude subsections more-lines]
                             (parse-prelude-and-sections more-lines (inc (count level)))]
                         (recur (conj sections
                                      #::{:header   whole-line
                                          :prelude  prelude
                                          :sections subsections})
                                more-lines))))))))
           lines 1)]
      #::{:prelude  prelude
          :sections sections})))

(defn write-file
  [writer file]
  (with-open [pw (java.io.PrintWriter. writer)]
    (binding [*out* pw]
      ((fn func [{::keys [prelude sections]}]
         (run! println prelude)
         (doseq [section sections]
           (println (::header section))
           (func section)))
       file))))

(defn indent-lines
  [level lines]
  (let [space (apply str (repeat (inc level) \space))]
    (map #(str space %) lines)))

(defn read-level
  [section]
  (->> (::header section)
       (take-while #{\*})
       (count)))

(s/def ::prelude-with-properties
  (s/cat :scheduling-line   (s/? ::line)
         :prop-start        #(re-matches #"(?i)\s*:PROPERTIES:\s*" %)
         :props             (s/* #(not (re-matches #"(?i)\s*:END:\s*" %)))
         :prop-end          #(re-matches #"(?i)\s*:END:\s*" %)
         :remaining-prelude (s/* ::line)))

(def prop-line-pattern #"(\s*):([^:\s]+):(\s+)(.*?)\s*")

(defn read-properties
  [section]
  (let [conformed (s/conform ::prelude-with-properties (::prelude section))]
    (if (= ::s/invalid conformed)
      {}
      (->> (:props conformed)
           (keep (fn [line]
                   (when-let [[_ _ k _ v] (re-matches prop-line-pattern line)]
                     [k v])))
           (into {})))))

(defn prop-assoc
  [section k v]
  (let [{::keys [prelude]} section
        conformed (s/conform ::prelude-with-properties prelude)]
    (if (= ::s/invalid conformed)
      (let [prop-lines (indent-lines (read-level section)
                                     [":PROPERTIES:"
                                      (format ":%s: %s" k v)
                                      ":END:"])]
        (assoc section ::prelude
               (if (some->> (first prelude)
                            (re-find #"DEADLINE|SCHEDULED"))
                 (concat (take 1 prelude) prop-lines (drop 1 prelude))
                 (concat prop-lines prelude))))

      (let [kv-pair-lines
            (loop [passed-props []
                   more-props (:props conformed)]
              (if (empty? more-props)
                ;; no matches, add a new line
                (concat passed-props
                        (indent-lines (read-level section)
                                      [(format ":%s: %s" k v)]))
                (or (if-let [[_ whitespace1 k' whitespace2 v']
                             (re-matches prop-line-pattern (first more-props))]
                      (if (= (string/lower-case k)
                             (string/lower-case k'))
                        ;; found a match; update this line
                        (concat passed-props
                                [(str whitespace1 ":" k' ":" whitespace2 v)]
                                (rest more-props))))
                    (recur (conj passed-props (first more-props))
                           (rest more-props)))
                ))]
        (assoc section ::prelude
               (concat (some-> (:scheduling-line conformed) vector)
                       [(:prop-start conformed)]
                       kv-pair-lines
                       [(:prop-end conformed)]
                       (:remaining-prelude conformed)))))))
