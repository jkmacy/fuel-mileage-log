(ns fuel-log.model
  (:require [clojure.string :as str]))

;; Existing record keys and backup formats are intentionally unchanged.
(defn pad [n] (.padStart (str n) 2 "0"))
(defn date-local [d] (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d))))
(defn time-local [d] (str (pad (.getHours d)) ":" (pad (.getMinutes d))))
(defn finite? [x] (js/Number.isFinite x))
(defn parse-number [v] (js/Number (str/replace (str (or v "")) #"[$,\s]" "")))
(defn parse-date [value]
  (let [s (str/trim (str (or value "")))
        m (re-matches #"(\d{1,2})/(\d{1,2})/(\d{4})" s)]
    (cond
      (re-matches #"\d{4}-\d{2}-\d{2}" s)
      (let [d (js/Date. (str s "T12:00:00"))]
        (when (and (finite? (.getTime d)) (= s (date-local d))) s))
      m (let [month (js/Number (nth m 1))
              day (js/Number (nth m 2))
              year (js/Number (nth m 3))
              d (js/Date. year (dec month) day)]
          (when (and (= (.getFullYear d) year) (= (.getMonth d) (dec month))
                     (= (.getDate d) day))
            (date-local d)))
      :else nil)))
(defn valid-values? [m t g c]
  (and (every? finite? [m t g c]) (>= m 0) (>= t 0) (> g 0) (> c 0)))
(defn fingerprint [r]
  (str (:date r) "|" (:mileage r) "|" (:trip r) "|" (:gallons r) "|" (:cost r)))
(defn record-date-time [r]
  (let [at (:recordedAt r) source (or (:sourceTimestamp r) "")
        match (re-matches #"(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})(?::\d{2})?" source)]
    (cond
      (and at (finite? (.getTime (js/Date. at))))
      (let [d (js/Date. at)] (str (date-local d) " " (time-local d)))
      match (str (nth match 3) "-" (pad (nth match 1)) "-" (pad (nth match 2))
                 " " (pad (nth match 4)) ":" (nth match 5))
      :else (:date r))))
(defn sort-entries [rs]
  (sort (fn [a b]
          (let [cmp (compare (:date b) (:date a))]
            (if (zero? cmp)
              (compare (or (:recordedAt b) (:sourceTimestamp b) "")
                       (or (:recordedAt a) (:sourceTimestamp a) "")) cmp))) rs))
(defn csv-rows [s]
  (let [rows (atom []) cells (atom []) cell (atom "") quoted? (atom false) n (count s)]
    (letfn [(finish-cell! [] (swap! cells conj @cell) (reset! cell ""))
            (finish-row! [] (finish-cell!)
              (when (some #(not= % "") @cells) (swap! rows conj @cells))
              (reset! cells []))]
      (loop [i 0]
        (if (< i n)
          (let [c (subs s i (inc i))
                nxt (when (< (inc i) n) (subs s (inc i) (+ i 2)))]
            (cond
              (= c "\"") (if (and @quoted? (= nxt "\""))
                            (do (swap! cell str "\"") (recur (+ i 2)))
                            (do (swap! quoted? not) (recur (inc i))))
              (and (= c ",") (not @quoted?)) (do (finish-cell!) (recur (inc i)))
              (and (or (= c "\n") (= c "\r")) (not @quoted?))
              (do (finish-row!)
                  (recur (if (and (= c "\r") (= nxt "\n")) (+ i 2) (inc i))))
              :else (do (swap! cell str c) (recur (inc i)))))
          (do (when @quoted? (throw (js/Error. "CSV has an unclosed quote")))
              (finish-row!) @rows))))))
(defn quote-csv [v]
  (let [s (str (or v ""))]
    (if (re-find #"[\",\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"") s)))
(defn export-csv [rs]
  (let [headers ["Recorded At" "Date" "Current Mileage" "Trip Miles" "Gallons"
                 "Cost" "Source Timestamp" "Origin"]
        rows (cons headers
                   (map (fn [r] [(or (:recordedAt r) "") (:date r) (:mileage r)
                                 (:trip r) (:gallons r) (:cost r)
                                 (or (:sourceTimestamp r) "") (or (:origin r) "app")]) rs))]
    (str (str/join "\r\n" (map #(str/join "," (map quote-csv %)) rows)) "\r\n")))
(defn field [headers row names]
  (or (some (fn [name] (let [i (.indexOf headers name)]
                         (when (>= i 0) (or (nth row i nil) "")))) names) ""))
(defn timestamp [raw]
  (when (and raw (not= raw ""))
    (let [d (js/Date. raw)]
      (when (finite? (.getTime d)) (.toISOString d)))))
(defn import-csv [text existing uid]
  (let [rows (csv-rows (str/replace text #"^\uFEFF" ""))
        headers (mapv #(str/lower-case (str/trim %)) (first rows))
        seen (js/Set. (into-array (map fingerprint existing)))
        added (atom []) duplicate (atom 0) invalid (atom 0)]
    (when (< (count rows) 2) (throw (js/Error. "No data rows found")))
    (doseq [name ["current mileage" "trip miles" "gallons" "cost"]]
      (when-not (some #{name} headers) (throw (js/Error. (str "Missing column: " name)))))
    (doseq [row (rest rows)]
      (let [date (parse-date (field headers row ["date"]))
            m (parse-number (field headers row ["current mileage"]))
            t (parse-number (field headers row ["trip miles"]))
            g (parse-number (field headers row ["gallons"]))
            c (parse-number (field headers row ["cost"]))]
        (if (and date (valid-values? m t g c))
          (let [r {:id (uid) :date date :mileage m :trip t :gallons g :cost c
                   :recordedAt (timestamp (field headers row ["recorded at" "recordedat"]))
                   :sourceTimestamp (str/trim (field headers row ["source timestamp" "timestamp"]))
                   :origin (or (not-empty (field headers row ["origin"])) "sheet")}
                fp (fingerprint r)]
            (if (.has seen fp) (swap! duplicate inc)
                (do (.add seen fp) (swap! added conj r))))
          (swap! invalid inc))))
    {:records @added :added (count @added) :duplicates @duplicate :invalid @invalid}))
(defn import-json [text existing uid]
  (let [obj (js/JSON.parse text)
        seen (js/Set. (into-array (map fingerprint existing)))
        added (atom []) duplicate (atom 0) invalid (atom 0)]
    (when-not (and (= "fuel-mileage-v1" (.-format obj))
                   (js/Array.isArray (.-entries obj)))
      (throw (js/Error. "Unrecognized JSON backup")))
    (doseq [raw (.-entries obj)]
      (let [date (parse-date (.-date raw))
            m (js/Number (.-mileage raw)) t (js/Number (.-trip raw))
            g (js/Number (.-gallons raw)) c (js/Number (.-cost raw))]
        (if (and date (valid-values? m t g c))
          (let [r {:id (uid) :date date :mileage m :trip t :gallons g :cost c
                   :recordedAt (timestamp (.-recordedAt raw))
                   :sourceTimestamp (str (or (.-sourceTimestamp raw) ""))
                   :origin (if (= "sheet" (.-origin raw)) "sheet" "app")}
                fp (fingerprint r)]
            (if (.has seen fp) (swap! duplicate inc)
                (do (.add seen fp) (swap! added conj r))))
          (swap! invalid inc))))
    {:records @added :added (count @added) :duplicates @duplicate :invalid @invalid}))
