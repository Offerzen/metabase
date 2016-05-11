(ns metabase.models.table
  (:require [korma.core :as k]
            [metabase.db :as db]
            (metabase.models [common :as common]
                             [database :refer [Database]]
                             [field :refer [Field]]
                             [field-values :refer [FieldValues]]
                             [interface :as i]
                             [metric :refer [Metric retrieve-metrics]]
                             [segment :refer [Segment retrieve-segments]])
            [metabase.util :as u]))

(def ^:const entity-types
  "Valid values for `Table.entity_type` (field may also be `nil`)."
  #{:person :event :photo :place})

(def ^:const visibility-types
  "Valid values for `Table.visibility_type` (field may also be `nil`)."
  #{:hidden :technical :cruft})

(i/defentity Table :metabase_table)

(defn- pre-insert [table]
  (let [defaults {:display_name (common/name->human-readable-name (:name table))}]
    (merge defaults table)))

(defn- pre-cascade-delete [{:keys [id]}]
  (db/cascade-delete Segment :table_id id)
  (db/cascade-delete Metric :table_id id)
  (db/cascade-delete Field :table_id id))

(defn ^:hydrate fields
  "Return the `FIELDS` belonging to TABLE."
  [{:keys [id]}]
  (db/sel :many Field :table_id id, :visibility_type [not= "retired"], (k/order :position :ASC) (k/order :name :ASC)))

(defn ^:hydrate metrics
  "Retrieve the metrics for TABLE."
  [{:keys [id]}]
  (retrieve-metrics id :all))

(defn ^:hydrate segments
  "Retrieve the segments for TABLE."
  [{:keys [id]}]
  (retrieve-segments id :all))

(defn field-values
  "Return the `FieldValues` for all `Fields` belonging to TABLE."
  {:hydrate :field_values, :arglists '([table])}
  [{:keys [id]}]
  (let [field-ids (db/sel :many :id Field, :table_id id, :visibility_type "normal"
                          (k/order :position :asc)
                          (k/order :name :asc))]
    (db/sel :many :field->field [FieldValues :field_id :values] :field_id [in field-ids])))

(defn pk-field-id
  "Return the ID of the primary key `Field` for TABLE."
  {:hydrate :pk_field, :arglists '([table])}
  [{:keys [id]}]
  (db/sel :one :id Field, :table_id id, :special_type "id", :visibility_type [not-in ["sensitive" "retired"]]))

(def ^{:arglists '([table])} database
  "Return the `Database` associated with this `Table`."
  (comp Database :db_id))

(u/strict-extend (class Table)
  i/IEntity (merge i/IEntityDefaults
                   {:hydration-keys     (constantly [:table])
                    :types              (constantly {:entity_type :keyword, :visibility_type :keyword, :description :clob})
                    :timestamped?       (constantly true)
                    :can-read?          (constantly true)
                    :can-write?         i/superuser?
                    :pre-insert         pre-insert
                    :pre-cascade-delete pre-cascade-delete}))


;; ## Persistence Functions

(defn table-id->database-id
  "Retrieve the `Database` ID for the given table-id."
  [table-id]
  {:pre [(integer? table-id)]}
  (db/sel :one :field [Table :db_id] :id table-id))


(defn retire-tables
  "Retire all `Tables` in the list of TABLE-IDs along with all of each tables `Fields`."
  [table-ids]
  {:pre [(set? table-ids)
         (every? integer? table-ids)]}
  ;; retire the tables
  (k/update Table
    (k/where {:id [in table-ids]})
    (k/set-fields {:active false}))
  ;; retire the fields of retired tables
  (k/update Field
    (k/where {:table_id [in table-ids]})
    (k/set-fields {:visibility_type "retired"})))

(defn update-table
  "Update `Table` with the data from TABLE-DEF."
  [{:keys [id display_name], :as existing-table} {table-name :name}]
  {:pre [(integer? id)]}
  (let [updated-table (assoc existing-table
                        :display_name (or display_name (common/name->human-readable-name table-name)))]
    ;; the only thing we need to update on a table is the :display_name, if it never got set
    (when (nil? display_name)
      (db/upd Table id
        :display_name (:display_name updated-table)))
    ;; always return the table when we are done
    updated-table))


(defn create-table
  "Create `Table` with the data from TABLE-DEF."
  [database-id {schema-name :schema, table-name :name, raw-table-id :raw-table-id, visibility-type :visibility-type}]
  (db/ins Table
    :db_id           database-id
    :raw_table_id    raw-table-id
    :schema          schema-name
    :name            table-name
    :visibility_type visibility-type
    :display_name    (common/name->human-readable-name table-name)
    :active          true))
