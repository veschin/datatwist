# Clojure.java.io Library Documentation

## Overview
`clojure.java.io` provides Clojure wrappers around Java's I/O facilities, offering idiomatic Clojure functions for file operations, stream handling, and resource management. It simplifies common I/O tasks while maintaining the power and flexibility of Java's I/O system.

## Key Features
- Simplified file operations
- Stream and reader/writer creation
- Resource loading from classpath
- URL and file coercion
- Copy and transfer operations
- Automatic resource management

---

## 1. File Operations

### Basic File Operations
```clojure
(require '[clojure.java.io :as io])

;; File creation and reference
(def my-file (io/file "path/to/file.txt"))
(def my-file (io/file "directory" "subdirectory" "file.txt"))

;; File information
(.exists my-file)                 ;=> true/false
(.isFile my-file)                 ;=> true/false
(.isDirectory my-file)            ;=> true/false
(.getName my-file)                ;=> "file.txt"
(.getAbsolutePath my-file)        ;=> Full path
```

### File Reading and Writing
```clojure
;; Reading entire file
(slurp "path/to/file.txt")        ;=> File content as string

;; Writing to file
(spit "path/to/output.txt" "Hello, World!")

;; Appending to file
(spit "path/to/output.txt" "\nMore content" :append true)

;; Reading lines
(defn read-lines [filename]
  (with-open [reader (io/reader filename)]
    (doall (line-seq reader))))

(read-lines "file.txt")           ;=> ["line1" "line2" "line3"]
```

---

## 2. Stream Operations

### Input/Output Streams
```clojure
;; Creating streams
(def input-stream (io/input-stream "file.txt"))
(def output-stream (io/output-stream "output.txt"))

;; Reading from stream
(defn read-bytes [input-stream]
  (with-open [in input-stream]
    (let [buffer (byte-array 1024)
          bytes-read (.read in buffer)]
      (take bytes-read buffer))))

;; Writing to stream
(defn write-bytes [output-stream data]
  (with-open [out output-stream]
    (.write out data)))
```

### Readers and Writers
```clojure
;; Creating readers and writers
(def reader (io/reader "input.txt"))
(def writer (io/writer "output.txt"))

;; Character encoding
(def utf8-reader (io/reader "file.txt" :encoding "UTF-8"))
(def utf8-writer (io/writer "file.txt" :encoding "UTF-8"))

;; Buffered operations
(def buffered-reader (io/reader (io/input-stream "file.txt")))
(def buffered-writer (io/writer (io/output-stream "file.txt")))
```

---

## 3. Resource Loading

### Classpath Resources
```clojure
;; Loading resources from classpath
(io/resource "config.properties")   ;=> URL object
(slurp (io/resource "config.properties")) ;=> Resource content

;; Loading from specific classloader
(io/resource "data.txt" (clojure.lang.RT/baseLoader))

;; Checking resource existence
(when-let [resource (io/resource "grammar.txt")]
  (println "Found grammar file:" resource))
```

### Resource Streams
```clojure
;; Opening resource as stream
(with-open [stream (io/input-stream (io/resource "data.bin"))]
  ;; Process stream
  )

;; Reading resource as reader
(with-open [reader (io/reader (io/resource "text.txt"))]
  (doseq [line (line-seq reader)]
    (println line)))
```

---

## 4. URL and File Coercion

### URL Operations
```clojure
;; Creating URLs
(def url (io/url "https://example.com/data.txt"))
(def file-url (io/url (io/file "local.txt")))

;; URL to file conversion
(def file-from-url (io/file url))

;; Reading from URL
(slurp "https://example.com/data.txt")

;; URL encoding
(def encoded-url (io/url "https://example.com/search?q=hello world"))
```

### Path Operations
```clojure
;; Working with paths
(def parent-dir (io/file "path/to"))
(def child-file (io/file parent-dir "child.txt"))

;; Absolute and relative paths
(def absolute-file (io/file "/absolute/path/file.txt"))
(def relative-file (io/file "relative/path/file.txt"))

;; Path normalization
(.getCanonicalPath (io/file "path/../to/file.txt"))
```

---

## 5. Copy and Transfer Operations

### File Copying
```clojure
;; Copy file to file
(io/copy (io/file "source.txt") (io/file "dest.txt"))

;; Copy stream to file
(with-open [in (io/input-stream "source.txt")]
  (io/copy in (io/file "dest.txt")))

;; Copy file to stream
(with-open [out (io/output-stream "dest.txt")]
  (io/copy (io/file "source.txt") out))

;; Copy stream to stream
(with-open [in (io/input-stream "source.txt")
            out (io/output-stream "dest.txt")]
  (io/copy in out))
```

### Advanced Copy Operations
```clojure
;; Copy with progress monitoring
(defn copy-with-progress [source dest progress-callback]
  (with-open [in (io/input-stream source)
              out (io/output-stream dest)]
    (let [buffer (byte-array 8192)
          total-size (.length (io/file source))
          bytes-copied (atom 0)]
      (loop [bytes-read (.read in buffer)]
        (when (pos? bytes-read)
          (.write out buffer 0 bytes-read)
          (swap! bytes-copied + bytes-read)
          (progress-callback @bytes-copied total-size)
          (recur (.read in buffer)))))))
```

---

## 6. Directory Operations

### Directory Management
```clojure
;; Creating directories
(.mkdirs (io/file "path/to/new/directory"))

;; Listing directory contents
(defn list-files [directory]
  (->> (.listFiles (io/file directory))
       (map #(.getName %))
       sort))

(list-files ".")                 ;=> ["file1.txt" "file2.txt" "subdir"]

;; Recursive directory listing
(defn list-files-recursively [directory]
  (let [file (io/file directory)]
    (if (.isDirectory file)
      (mapcat #(list-files-recursively (.getPath %)) (.listFiles file))
      [(.getPath file)])))
```

### File Filtering
```clojure
;; Filter files by extension
(defn files-by-extension [directory extension]
  (->> (.listFiles (io/file directory))
       (filter #(.endsWith (.getName %) extension))
       (map #(.getPath %))))

(files-by-extension "." ".clj")    ;=> ["file1.clj" "file2.clj"]

;; Filter with custom predicate
(defn filter-files [directory predicate]
  (->> (.listFiles (io/file directory))
       (filter predicate)
       (map #(.getPath %))))
```

---

## 7. Temporary Files

### Temporary File Operations
```clojure
;; Create temporary file
(def temp-file (java.io.File/createTempFile "prefix" ".suffix"))
(.deleteOnExit temp-file)          ; Clean up on JVM exit

;; Temporary directory
(def temp-dir (.mkdirs (io/file (System/getProperty "java.io.tmpdir") "myapp")))

;; Using temporary files safely
(defn with-temp-file [filename-prefix content f]
  (let [temp-file (java.io.File/createTempFile filename-prefix ".tmp")]
    (try
      (spit temp-file content)
      (f temp-file)
      (finally
        (.delete temp-file)))))

(with-temp-file "test" "Hello, World!" 
  (fn [f] (println "Temp file:" (.getPath f))))
```

---

## 8. Character Encoding

### Encoding Handling
```clojure
;; Reading with specific encoding
(with-open [reader (io/reader "file.txt" :encoding "UTF-8")]
  (slurp reader))

;; Writing with specific encoding
(with-open [writer (io/writer "output.txt" :encoding "UTF-8")]
  (.write writer "Unicode text: café"))

;; Detecting file encoding (requires external library)
;; Using junidecode or similar for encoding detection
```

---

## 9. Network I/O

### HTTP Operations
```clojure
;; Reading from URL
(defn fetch-url [url-string]
  (with-open [stream (io/input-stream url-string)]
    (slurp stream)))

(fetch-url "https://example.com")  ;=> HTML content

;; Downloading files
(defn download-file [url-string dest-path]
  (with-open [in (io/input-stream url-string)
              out (io/output-stream dest-path)]
    (io/copy in out)))
```

---

## 10. Error Handling and Resource Management

### Safe File Operations
```clojure
;; Safe file reading with error handling
(defn safe-read-file [filename]
  (try
    (when (.exists (io/file filename))
      (slurp filename))
    (catch java.io.IOException e
      (println "Error reading file:" (.getMessage e))
      nil)))

;; Safe file writing
(defn safe-write-file [filename content]
  (try
    (spit filename content)
    true
    (catch java.io.IOException e
      (println "Error writing file:" (.getMessage e))
      false)))
```

### Resource Management Patterns
```clojure
;; Using with-open for automatic resource cleanup
(defn process-file [filename processor-fn]
  (with-open [reader (io/reader filename)]
    (processor-fn reader)))

;; Multiple resources
(defn copy-file-with-logging [source dest]
  (with-open [in (io/input-stream source)
              out (io/output-stream dest)]
    (println "Copying" source "to" dest)
    (io/copy in out)
    (println "Copy complete")))
```

---

## 11. Performance Considerations

### Buffering and Optimization
```clojure
;; Buffered reading for large files
(defn read-large-file [filename]
  (with-open [reader (io/reader (io/input-stream filename) 
                                :buffer-size 8192)]
    (doall (line-seq reader))))

;; Memory-efficient file processing
(defn process-large-file [filename process-line-fn]
  (with-open [reader (io/reader filename)]
    (doseq [line (line-seq reader)]
      (process-line-fn line))))

;; Using nio for better performance
(defn fast-copy [source dest]
  (let [source-path (.toPath (io/file source))
        dest-path (.toPath (io/file dest))]
    (java.nio.file.Files/copy source-path dest-path 
                              (into-array java.nio.file.CopyOption []))))
```

---

## 12. Common Patterns and Utilities

### File Utilities
```clojure
;; Ensure directory exists
(defn ensure-directory [directory-path]
  (let [dir (io/file directory-path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    dir))

;; Backup file
(defn backup-file [filename]
  (let [original (io/file filename)
        backup (io/file (str filename ".backup"))]
    (io/copy original backup)
    (.getPath backup)))

;; File size
(defn file-size [filename]
  (let [file (io/file filename)]
    (when (.exists file)
      (.length file))))
```

### Configuration Loading
```clojure
;; Load configuration from file
(defn load-config [config-file]
  (when-let [resource (io/resource config-file)]
    (read-string (slurp resource))))

;; Load properties file
(defn load-properties [properties-file]
  (when-let [resource (io/resource properties-file)]
    (with-open [reader (io/reader resource)]
      (let [props (java.util.Properties.)]
        (.load props reader)
        (into {} props)))))
```

---

## Usage Notes for DataTwist Project

1. **Grammar Files**: Use `slurp` to read grammar files and `spit` to write generated grammars
2. **Resource Loading**: Use `io/resource` to load grammar templates from classpath
3. **File Processing**: Use `with-open` for safe file handling during parsing
4. **Configuration**: Load configuration files using resource loading mechanisms
5. **Error Handling**: Always wrap file operations in try-catch blocks
6. **Performance**: Use buffered readers for large grammar files
7. **Temporary Files**: Use temporary files for intermediate processing steps

This comprehensive I/O documentation provides all the necessary tools for effective file and resource management in your DataTwist grammar parsing project.