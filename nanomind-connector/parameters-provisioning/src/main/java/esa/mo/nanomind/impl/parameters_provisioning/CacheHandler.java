// (C) 2020 European Space Agency
// European Space Operations Centre
// Darmstadt, Germany

package esa.mo.nanomind.impl.parameters_provisioning;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.ccsds.moims.mo.mal.structures.Attribute;
import org.ccsds.moims.mo.mal.structures.Identifier;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWParameter;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWParameterValuesProvider;

/**
 * Provides OBSW parameter values through a caching mechanism. For a given parameter, it only
 * returns a non-null value if a value for this parameter was previously cached.
 *
 * @author Tanguy Soto
 */
public class CacheHandler extends OBSWParameterValuesProvider {

  /**
   * Map of OBSW parameter value by parameter name acting as our cache storage.
   */
  private final Map<Identifier, TimedAttributeValue> cache;

  /*
   * Cache configuration settings
   */

  /**
   * Maximum time a parameter value should stay in the cache in seconds.
   */
  private long cachingTime = 10;

  /**
   * Creates a new instance of CacheHandler.
   * 
   * @param parameterMap
   */
  public CacheHandler(HashMap<Identifier, OBSWParameter> parameterMap) {
    super(parameterMap);
    cache = new HashMap<Identifier, TimedAttributeValue>();
  }

  /**
   * Sets the maximum time a parameter value should stay in the cache in seconds.
   * 
   * @param cachingTime the time
   */
  public void setCachingTime(long cachingTime) {
    this.cachingTime = cachingTime;
  }

  /**
   * Returns true if the cached value of this parameter has to be refreshed according to the cache
   * settings.
   *
   * @param identifier Name of the parameter
   * @return A boolean
   */
  public synchronized boolean mustRefreshValue(Identifier identifier) {
    // Value for this parameter has never been cached
    if (!cache.containsKey(identifier)) {
      return true;
    }

    long now = System.currentTimeMillis();

    // This parameter value is outdated
    if (now - cache.get(identifier).getLastUpdateTime().getTime() > cachingTime * 1000) {
      return true;
    }

    // No need to refresh, cached value is still valid.
    return false;
  }

  /**
   * Returns the date and time at which the parameter was last updated in the cache.
   *
   * @param identifier Name of the parameter
   * @return A Date object or null if the parameter was never cached before
   */
  public synchronized Date getLastUpdateTime(Identifier identifier) {
    if (!cache.containsKey(identifier)) {
      return null;
    }

    return cache.get(identifier).getLastUpdateTime();
  }

  /** {@inheritDoc} */
  @Override
  public synchronized Attribute getValue(Identifier identifier) {
    if (!cache.containsKey(identifier)) {
      return null;
    }
    return cache.get(identifier).getValue();
  }

  /**
   * Caches a value for a given OBSW parameter name
   *
   * @param value Value to cache
   * @param identifier Name of the parameter
   */
  public synchronized void cacheValue(Attribute value, Identifier identifier) {
    if (!cache.containsKey(identifier)) {
      cache.put(identifier, new TimedAttributeValue(value));
    } else {
      cache.get(identifier).setValue(value);
    }
  }
}