// (C) 2020 European Space Agency
// European Space Operations Centre
// Darmstadt, Germany

package esa.mo.nanomind.impl.parameters_provisioning;

import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ccsds.moims.mo.mal.structures.Attribute;
import org.ccsds.moims.mo.mal.structures.Identifier;
import esa.mo.helpertools.helpers.HelperAttributes;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWParameter;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWParameterValuesProvider;

/**
 * Provides OBSW parameter values by consuming the Nanomind aggregation service. Fetched values are
 * placed in a cache and aggregation service is used with restrictions to avoid overloading the Nanomind.
 *
 * @author Tanguy Soto
 */
public class NanomindParameterValuesProvider extends OBSWParameterValuesProvider {

  /**
   * Creates a new instance of CacheParameterValuesProvider.
   * 
   * @param parameterMap The map of OBSW parameters for which we have to provide values for
   */
  public NanomindParameterValuesProvider(HashMap<Identifier, OBSWParameter> parameterMap) {
    super(parameterMap);
    this.cacheHandler = new CacheHandler(parameterMap);
    this.cacheHandler.setCachingTime(10 * 1000);
  }

  /**
   * The logger
   */
  private static final Logger LOGGER =
      Logger.getLogger(NanomindParameterValuesProvider.class.getName());

  /**
   * Object handling caching of the values
   */
  private final CacheHandler cacheHandler;


  /**
   * 
   * TODO getNewValue
   *
   * @param identifier
   * @return
   */
  private Attribute getNewValue(Identifier identifier) {
    LOGGER.log(Level.INFO, "getNewValue(" + identifier + ") called");

    if (!parameterMap.containsKey(identifier)) {
      return null;
    }
    
    OBSWParameter param = parameterMap.get(identifier);
    return HelperAttributes.attributeName2Attribute(param.getType());
  }

  /** {@inheritDoc} */
  @Override
  public Attribute getValue(Identifier identifier) {
    if (cacheHandler.mustRefreshValue(identifier)) {
      Attribute newValue = getNewValue(identifier);
      cacheHandler.cacheValue(newValue, identifier);
      return newValue;
    }
    return cacheHandler.getValue(identifier);
  }
}
