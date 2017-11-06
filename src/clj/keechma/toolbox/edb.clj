(ns keechma.toolbox.edb)

(defmacro defentitydb [schema]
  (let [getter-fns
        [:get-item-by-id
         :get-named-item
         :get-item-meta
         :get-named-item-meta
         :get-collection-meta]
        getter-ensure-layout-fns
        [:get-collection]
        mutate-fns
        [:insert-meta
         :remove-meta
         :remove-named-item
         :remove-collection
         :empty-collection
         :remove-related-collection
         :vacuum]
        mutate-with-schema-fns
        [:insert-item
         :insert-named-item
         :insert-collection
         :append-collection
         :prepend-collection
         :remove-item
         :insert-related-collection
         :append-related-collection
         :prepend-related-collection]]
    `(do
       ~@(for [fn-name getter-ensure-layout-fns]
           (let [db-sym (gensym "db")
                 rest-sym (gensym "rest")]
             `(def ~(symbol (name fn-name))
                (fn [~db-sym & ~rest-sym]
                  (apply
                   (partial 
                    (~(symbol "entitydb.util" "ensure-layout") ~(symbol "entitydb.core" (name fn-name)))
                    ~schema)
                   (concat [(:entity-db ~db-sym)] ~rest-sym))))))
        ~@(for [fn-name getter-fns]
           (let [db-sym (gensym "db")
                 rest-sym (gensym "rest")]
             `(def ~(symbol (name fn-name))
                (fn [~db-sym & ~rest-sym]
                  (apply
                   (partial 
                    ~(symbol "entitydb.core" (name fn-name))
                    ~schema)
                   (concat [(:entity-db ~db-sym)] ~rest-sym))))))
        ~@(for [fn-name mutate-with-schema-fns]
           (let [db-sym (gensym "db")
                 rest-sym (gensym "rest")]
             `(def ~(symbol (name fn-name))
                (fn [~db-sym & ~rest-sym]
                  (assoc
                   ~db-sym
                   :entity-db
                   (apply
                    (partial 
                     (~(symbol "entitydb.util" "ensure-layout") ~(symbol "entitydb.core" (name fn-name)))
                     ~schema)
                    (concat [(:entity-db ~db-sym)] ~rest-sym)))))))
        ~@(for [fn-name mutate-fns]
           (let [db-sym (gensym "db")
                 rest-sym (gensym "rest")]
             `(def ~(symbol (name fn-name))
                (fn [~db-sym & ~rest-sym]
                  (assoc
                   ~db-sym
                   :entity-db
                   (apply
                    ~(symbol "entitydb.core" (name fn-name))
                    (concat [(:entity-db ~db-sym)] ~rest-sym))))))))))
