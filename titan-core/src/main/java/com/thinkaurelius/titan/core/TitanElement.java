package com.thinkaurelius.titan.core;


import com.thinkaurelius.titan.util.datastructures.Removable;
import com.tinkerpop.blueprints.Element;

/**
 * TitanElement represents the abstract concept of an entity in the graph and specifies basic methods for interacting
 * with entities.
 * The two basic entities in a graph database are {@link TitanRelation} and {@link TitanVertex}.
 * Entities have a life cycle state which reflects the current state of the entity with respect
 * to the {@link TitanTransaction} in which it occurs. An entity may be in any one of these states
 * and any given time:
 * <ul>
 * <li><strong>New:</strong> The entity has been created in the current transaction</li>
 * <li><strong>Loaded:</strong> The entity has been loaded from disk into the current transaction and has not been modified</li>
 * <li><strong>Modified:</strong> The entity has been loaded from disk into the current transaction and has been modified</li>
 * <li><strong>Deleted:</strong> The entity has been deleted in the current transaction</li>
 * </ul>
 * Depending on the concrete type of the entity, an entity may be identifiable,
 * i.e. it has a unique ID which can be retrieved via {@link #getId}
 * (use {@link #hasId} to determine if a given entity has a unique ID).
 *
 * @author Matthias Br&ouml;cheler (http://www.matthiasb.com)
 * @see TitanVertex
 * @see TitanRelation
 */
public interface TitanElement extends Element, Idfiable, Removable {

    /**
     * Returns a unique identifier for this entity.
     * <p/>
     * The unique identifier may only be set when the transaction in which entity is created commits.
     * Some entities are never assigned a unique identifier if they depend on a parent entity.
     * <p/>
     * Titan allocates blocks of identifiers and automatically assigns identifiers to elements
     * automatically be default.  This behavior can be partially overridden by setting
     * {@link com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration#ALLOW_SETTING_VERTEX_ID}
     *
     * @return The unique identifier for this entity
     * @throws IllegalStateException if the entity does not (yet) have a unique identifier
     * @see #hasId
     */
    public Object getId();

    /**
     * Unique identifier for this entity. This id can be temporarily assigned and might change.
     * Use {@link #getId()} for the permanent id.
     *
     * @return Unique long id
     */
    public long getLongId();

    /**
     * Checks whether this entity has a unique identifier.
     * <p/>
     * Note that some entities may never be assigned an identifier and others will only be
     * assigned an identifier at the end of a transaction.
     *
     * @return true if this entity has been assigned a unique id, else false
     * @see #getLongId()
     */
    public boolean hasId();

    /**
     * Deletes this entity and any incident edges or properties from the graph.
     * <p/>
     *
     * @throws IllegalStateException if the entity cannot be deleted or if the user does not
     *                               have permission to remove the entity
     */
    public void remove();

    /**
     * Sets the value for the given key on this element.
     * The key must be defined to have {@link Cardinality#SINGLE}, otherwise this method throws an exception.
     *
     * @param key   the string identifying the key
     * @param value the object value
     */
    public void setProperty(String key, Object value);

    /**
     * Sets the value for the given key on this element.
     * The key must be defined to have {@link Cardinality#SINGLE}, otherwise this method throws an exception.
     *
     * @param key   the key
     * @param value the object value
     */
    public void setProperty(PropertyKey key, Object value);

    /**
     * Retrieves the value associated with the given key on this vertex and casts it to the specified type.
     * If the key has cardinality SINGLE, then there can be at most one value and this value is returned (or null).
     * Otherwise a list of all associated values is returned, or an empty list if non exist.
     * <p/>
     *
     * @param key key
     * @return value or list of values associated with key
     */
    public <O> O getProperty(PropertyKey key);

    /**
     * Retrieves the value associated with the given key on this vertex and casts it to the specified type.
     * If the key has cardinality SINGLE, then there can be at most one value and this value is returned (or null).
     * Otherwise a list of all associated values is returned, or an empty list if non exist.
     * <p/>
     *
     * @param key string identifying a key
     * @return value or list of values associated with key
     */
    public <O> O getProperty(String key);

    /**
     * Removes the value associated with the given key for this vertex (if exists).
     *
     * @param key the string identifying the key
     * @return the object value associated with that key prior to removal, or NULL if such does not exist.
     * @see #removeProperty(RelationType)
     */
    public <O> O removeProperty(String key);

    /**
     * Removes the value associated with the given key for this vertex (if exists).
     * If the key is list-valued then all values associated with this key are removed and only
     * the last removed value returned.
     *
     * @param type the key
     * @return the object value associated with that key prior to removal, or NULL if such does not exist.
     */
    public <O> O removeProperty(RelationType type);


    //########### LifeCycle Status ##########

    /**
     * Checks whether this entity has been newly created in the current transaction.
     *
     * @return True, if entity has been newly created, else false.
     */
    public boolean isNew();

    /**
     * Checks whether this entity has been loaded into the current transaction and not yet modified.
     *
     * @return True, has been loaded and not modified, else false.
     */
    public boolean isLoaded();

    /**
     * Checks whether this entity has been deleted into the current transaction.
     *
     * @return True, has been deleted, else false.
     */
    public boolean isRemoved();

}
