// (C) 2020 European Space Agency
// European Space Operations Centre
// Darmstadt, Germany

package esa.mo.nanomind.impl.parameters_provisioning;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ccsds.moims.mo.mal.MALException;
import org.ccsds.moims.mo.mal.MALInteractionException;
import org.ccsds.moims.mo.mal.structures.Attribute;
import org.ccsds.moims.mo.mal.structures.Duration;
import org.ccsds.moims.mo.mal.structures.Identifier;
import org.ccsds.moims.mo.mal.structures.IdentifierList;
import org.ccsds.moims.mo.mal.structures.LongList;
import org.ccsds.moims.mo.mal.structures.URI;
import esa.mo.helpertools.connections.SingleConnectionDetails;
import esa.mo.nanomind.impl.consumer.AggregationNanomindConsumerServiceImpl;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWAggregation;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWParameter;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWParameterValuesProvider;
import esa.opssat.nanomind.mc.aggregation.body.GetValueResponse;
import esa.opssat.nanomind.mc.aggregation.structures.AggregationCategory;
import esa.opssat.nanomind.mc.aggregation.structures.AggregationDefinition;
import esa.opssat.nanomind.mc.aggregation.structures.AggregationDefinitionList;
import esa.opssat.nanomind.mc.aggregation.structures.AggregationReference;
import esa.opssat.nanomind.mc.aggregation.structures.AggregationReferenceList;
import esa.opssat.nanomind.mc.aggregation.structures.AggregationValue;
import esa.opssat.nanomind.mc.aggregation.structures.AggregationValueList;
import esa.opssat.nanomind.mc.aggregation.structures.factory.AggregationCategoryFactory;

/**
 * Provides OBSW parameter values by consuming the Nanomind aggregation service. Fetched values are
 * placed in a cache and aggregation service is used with restrictions to avoid overloading the
 * Nanomind.
 *
 * @author Tanguy Soto
 */
public class NanomindParameterValuesProvider extends OBSWParameterValuesProvider {
  /**
   * The logger
   */
  private static final Logger LOGGER =
      Logger.getLogger(NanomindParameterValuesProvider.class.getName());

  /**
   * Object handling caching of the values
   */
  private CacheHandler cacheHandler;

  /**
   * Time (seconds) a value stays in the cache before requesting a new one for each parameter
   */
  private static final int CACHING_TIME = 10;

  /**
   * Maximum number of parameter in an aggregation
   */
  private static final int AGGREGATION_DEF_MAX_PARAM = 5;

  /*
   * Maximum number of aggregation that we can define.
   */
  private static final int MAX_AGGREGATION_DEFINED = 1000;

  /**
   * Prefix for name of all aggregation definitions we generate.
   */
  private static final String AGG_DEF_NAME_PREFIX = "Z";

  /**
   * Nanomind aggregation service consumer
   */
  private AggregationNanomindConsumerServiceImpl aggServiceCns;

  private static final String NANOMIND_APID = "10"; // Default Nanomind APID (on 13 June 2016)
  private static final String MAL_SPP_BINDINDING = "malspp"; // Use the SPP Implementation
  private static final String SOURCE_ID = "0"; // OBSW supports any value. By default it is set to 0

  /**
   * Lock for the aggregation definitions
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * List of the aggregations currently defined by this class in the Nanomind.
   */
  private final List<OBSWAggregation> nanomindDefinitions = new ArrayList<>();

  /**
   * Parameter is removed from its associated aggregation if it was not queried after this amount of
   * time (seconds)
   */
  private static final int DEAGGREGATION_TIME = 60;

  /**
   * Interval (seconds) between attempts to clean aggregations definitions.
   */
  private static final int AGGREGATION_CLEANING_INTERVAL = 30;

  /**
   * Creates a new instance of CacheParameterValuesProvider.
   * 
   * @param parameterMap The map of OBSW parameters for which we have to provide values for
   */
  public NanomindParameterValuesProvider(HashMap<Identifier, OBSWParameter> parameterMap) {
    super(parameterMap);
    initCacheHandler(parameterMap);
    initAggregationServiceConsumer();
    scheduleAggregationsCleaner();
  }

  /**
   * Initializes the cache handler.
   *
   * @param parameterMap The map of OBSW parameters for which we have to provide values for
   */
  private void initCacheHandler(HashMap<Identifier, OBSWParameter> parameterMap) {
    this.cacheHandler = new CacheHandler(parameterMap);
    this.cacheHandler.setCachingTime(CACHING_TIME);
  }

  /**
   * Initializes the Nanomind aggregation service consumer
   */
  private void initAggregationServiceConsumer() {
    // Connection details to Nanomind aggregation service
    SingleConnectionDetails details = new SingleConnectionDetails();
    IdentifierList domain = new IdentifierList();
    domain.add(new Identifier("OPSSAT"));
    details.setDomain(domain);
    URI brokerUri = null;
    details.setBrokerURI(brokerUri);
    details.setProviderURI(MAL_SPP_BINDINDING + ":247/" + NANOMIND_APID + "/" + SOURCE_ID);

    try {
      this.aggServiceCns = new AggregationNanomindConsumerServiceImpl(details);
    } catch (MALException | MalformedURLException ex) {
      LOGGER.log(Level.SEVERE, "Couldn't initialize the nanomind aggregation service consumer", ex);
    }
  }


  /**
   * Starts the periodic cleaning of the aggregations.
   */
  private void scheduleAggregationsCleaner() {
    Timer timer = new Timer(true);
    timer.schedule(new TimerTask() {
      @Override
      public void run() {
        cleanAggregations(DEAGGREGATION_TIME);
      }
    }, AGGREGATION_CLEANING_INTERVAL * 1000, AGGREGATION_CLEANING_INTERVAL * 1000);
  }

  /**
   * Calls the getValue operation of the Nanomind aggregation service for the given aggregation.
   *
   * @param aggId The aggregation ID
   * @return the aggregation value or null in case a MAL exception was raised
   */
  private AggregationValue getNanomindAggregationValue(long aggId) {
    LongList aggIds = new LongList();
    aggIds.add(aggId);
    try {
      LOGGER.log(Level.INFO,
          String.format("calling getValue(%d) from Nanomind Agg. service consumer", aggId));
      GetValueResponse valueResponse = aggServiceCns.getAggregationNanomindStub().getValue(aggIds);
      LOGGER.log(Level.INFO,
          String.format("Agg. value for agg. id %d fetched from Nanomind", aggId));

      AggregationValueList aggValueList = valueResponse.getBodyElement1();
      AggregationValue aggValue = aggValueList.get(0);
      return aggValue;
    } catch (MALInteractionException | MALException e) {
      LOGGER.log(Level.SEVERE,
          "Error while calling getValue operation of Nanoming aggregation service", e);
      return null;
    }
  }

  /**
   * Parses parameters values from an aggregation value and update the cached values for those
   * parameters. Also returns the value of the parameter the aggregation was originally requested
   * for.
   *
   * @param aggValue The aggregation value to parse
   * @param agg Information about the OBSW aggregation
   * @param identifier Name of the parameter the aggregation was requested for
   * @return Value of the parameter the aggregation was requested for, null if the aggregation value
   *         passed is null
   */
  private Attribute retrieveValueAndUpdateCache(AggregationValue aggValue, OBSWAggregation agg,
      Identifier identifier) {
    // Nanomind aggregation service getValue operation returned null
    if (aggValue == null) {
      return null;
    }

    Attribute paramValue = null;

    // Parameter values are in the same order as in the aggregation definition
    for (int i = 0; i < aggValue.getValues().size(); i++) {
      Attribute value = aggValue.getValues().get(i).getRawValue();
      Identifier paramName = new Identifier(agg.getParameters().get(i).getName());

      // Return the requested parameter
      if (paramName.equals(identifier)) {
        paramValue = value;
      }
      // A whole aggregation is returned, take the chance to update every parameters
      cacheHandler.cacheValue(value, paramName);
      LOGGER.log(Level.INFO, String.format("Cached value %s for parameter %s", value, paramName));
    }

    return paramValue;
  }

  /**
   * Assign the given OBSW parameter to an aggregation. It takes care of adding/updating the
   * aggregation definition in the Nanomind.
   *
   * @param obswParameter The parameter we want to add to an aggregation
   */
  private void assignAggregationToParameter(OBSWParameter obswParameter) {
    boolean isAssigned = false;
    OBSWAggregation obswAgg = findAvailableAggregation(obswParameter);

    // No existing aggregation can hold this parameter
    if (obswAgg == null) {
      obswAgg = createAggregationForParameter(obswParameter);
      if (obswAgg != null) {
        isAssigned = addDefinitionInNanomind(obswAgg);
      }
    }
    // Update an existing aggregation
    else {
      obswAgg.getParameters().add(obswParameter);
      isAssigned = updateDefinitionInNanomind(obswAgg);
      // If update failed, don't leave the parameter in our local definition object
      if (!isAssigned) {
        obswAgg.getParameters().remove(obswAgg.getParameters().size());
      }
    }

    if (isAssigned) {
      obswParameter.setAggregation(obswAgg);
    }
  }

  /**
   * Iterates over the aggregation already defined in the Nanomind to see if one is empty enough to
   * include the new given parameter.
   *
   * @param obswParameter The parameter to include
   * @return The aggregation or null if no aggregation is empty enough
   */
  private OBSWAggregation findAvailableAggregation(OBSWParameter obswParameter) {
    for (OBSWAggregation agg : nanomindDefinitions) {
      // TODO filter aggregations by total size instead of parameters count
      if (agg.getParameters().size() < AGGREGATION_DEF_MAX_PARAM) {
        return agg;
      }
    }

    return null;
  }

  /**
   * Creates a new aggregation containing the given parameter.
   *
   * @param obswParameter The parameter to include in the new aggregation
   * @return The aggregation or null if we reached the limit of aggregations definitions
   */
  private OBSWAggregation createAggregationForParameter(OBSWParameter obswParameter) {
    if (nanomindDefinitions.size() >= MAX_AGGREGATION_DEFINED) {
      LOGGER.log(Level.SEVERE,
          String.format(
              "Max number of aggregation definitions reached, can't fetch value of parameter %s",
              obswParameter.getName()));
      return null;
    }

    OBSWAggregation newAggregation = new OBSWAggregation();
    newAggregation.setId(-1); // will be provided by the Nanomind
    newAggregation.setDynamic(false);
    newAggregation.setBuiltin(false);
    newAggregation.setName(nextAggregationDefinitionName());
    newAggregation.setDescription("Gen. by supervisor");
    newAggregation.setCategory(new AggregationCategoryFactory().createElement().toString());
    newAggregation.setUpdateInterval(0);
    newAggregation.setGenerationEnabled(false);
    newAggregation.getParameters().add(obswParameter);

    return newAggregation;
  }

  private String nextAggregationDefinitionName() {
    return getAggregationIdentifier(nanomindDefinitions.size());
  }

  private String getAggregationIdentifier(int i) {
    return String.format(AGG_DEF_NAME_PREFIX + "%03d", i);
  }

  /**
   * Updates an existing aggregation definition in the Nanomind.
   *
   * @param updatedAggregation The new version of the aggregation
   * @return True if the update succeeded, false otherwise
   */
  private boolean updateDefinitionInNanomind(OBSWAggregation updatedAggregation) {
    LongList ids = new LongList(1);
    ids.add(updatedAggregation.getId());

    AggregationDefinitionList aggList = toAggregationDefinitionList(updatedAggregation);

    try {
      LOGGER.log(Level.INFO,
          String.format("calling updateDefinition(%s) from Nanomind Agg. service consumer",
              updatedAggregation.getName()));
      aggServiceCns.getAggregationNanomindStub().updateDefinition(ids, aggList);
      LOGGER.log(Level.INFO,
          String.format("Agg. definition %s updated in Nanomind", updatedAggregation.getName()));
    } catch (MALInteractionException | MALException e) {
      // Aggregation couldn't be updated to the Nanomind
      LOGGER.log(Level.SEVERE,
          "Error while calling updateDefinition operation of Nanoming aggregation service", e);
      return false;
    }

    return true;
  }

  /**
   * Registers the given aggregation to the aggregation definitions in the Nanomind.
   *
   * @param newAggregation The aggregation to register
   * @return True if the registration succeeded, false otherwise
   */
  private boolean addDefinitionInNanomind(OBSWAggregation newAggregation) {
    LongList ids;
    AggregationDefinitionList list = toAggregationDefinitionList(newAggregation);

    try {
      LOGGER.log(Level.INFO,
          String.format("calling addDefinition(%s) from Nanomind Agg. service consumer",
              newAggregation.getName()));
      ids = aggServiceCns.getAggregationNanomindStub().addDefinition(list);
      LOGGER.log(Level.INFO,
          String.format("Agg. definition %s added in Nanomind", newAggregation.getName()));
    } catch (MALInteractionException | MALException e) {
      // Aggregation couldn't be added to the Nanomind
      LOGGER.log(Level.SEVERE,
          "Error while calling addDefinition operation of Nanoming aggregation service", e);
      return false;
    }

    // Aggregation was properly added to the Nanomind
    newAggregation.setId(ids.get(0));
    nanomindDefinitions.add(newAggregation);

    return true;
  }

  /**
   * Converts an OBSW aggregation object holder into an AggregationDefinitionList.
   *
   * @param obswAggregation The aggregation to convert
   * @return The aggregation definition list
   */
  private AggregationDefinitionList toAggregationDefinitionList(OBSWAggregation obswAggregation) {
    // Prepare list of parameters
    LongList paramIds = new LongList();
    for (OBSWParameter p : obswAggregation.getParameters()) {
      paramIds.add(p.getId());
    }

    // Aggregation reference
    AggregationReferenceList paramsSetList = new AggregationReferenceList();
    AggregationReference paramsSet =
        new AggregationReference(null, paramIds, new Duration(0), null);
    paramsSetList.add(paramsSet);

    // Aggregation definition
    AggregationDefinition def = new AggregationDefinition(new Identifier(obswAggregation.getName()),
        obswAggregation.getDescription(), AggregationCategory.GENERAL,
        obswAggregation.isGenerationEnabled(), new Duration(obswAggregation.getUpdateInterval()),
        false, new Duration(), paramsSetList);

    // Finally the list
    AggregationDefinitionList list = new AggregationDefinitionList();
    list.add(def);

    return list;
  }

  /**
   * Fetches a new value for the given parameter by querying the Nanomind aggregation service.
   * Internally we define an aggregation including the parameter if not included in one yet and get
   * the value of this aggregation. The size and number of those aggregations are limited.fs
   * 
   * @param identifier The parameter name
   * @return The value or null if the parameter is unknown or a problem occurred while fetching the
   *         value
   */
  private Attribute getNewValue(Identifier identifier) {
    // Parameter is unknown
    if (!parameterMap.containsKey(identifier)) {
      return null;
    }

    OBSWParameter obswParam = parameterMap.get(identifier);

    // If parameter is not included in an aggregation we assign it to one
    if (obswParam.getAggregation() == null) {
      assignAggregationToParameter(obswParam);
    }
    // If assignment failed (nanomind rejected update or creation of aggregation), give up
    if (obswParam.getAggregation() == null) {
      return null;
    }

    // Query the nanomind using the aggregation service
    OBSWAggregation agg = obswParam.getAggregation();
    AggregationValue aggValue = getNanomindAggregationValue(agg.getId());
    return retrieveValueAndUpdateCache(aggValue, agg, identifier);
  }

  /**
   * TODO cleanAggregations
   *
   * @param parameterTimeout
   */
  private void cleanAggregations(int parameterTimeout) {
    lock.lock();
    try {
      String message = "Locally defined aggregations:\n";
      for (OBSWAggregation obswAgg : nanomindDefinitions) {
        message += (obswAgg.toString() + "\n");
        for (OBSWParameter p : obswAgg.getParameters()) {
          message += (p.toString() + "\n");
        }
      }
      LOGGER.log(Level.INFO, message);

      message = "Remotely (Nanomind) defined aggregations:\n";
      IdentifierList identifierList = new IdentifierList();
      for (int i = 0; i < 5; i++) {
        identifierList.add(new Identifier(getAggregationIdentifier(i)));
      }
      try {
        LongList idsList =
            aggServiceCns.getAggregationNanomindStub().listDefinition(identifierList);
        if (idsList == null) {
          message += "";
        } else {
          for (Long id : idsList) {
            message += String.format("AggregationDefinition[ID=%s]\n", id);
          }
        }
        LOGGER.log(Level.INFO, message);
      } catch (MALInteractionException | MALException e) {
        LOGGER.log(Level.SEVERE,
            "Error while calling listDefinition operation of Nanoming aggregation service", e);
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Attribute getValue(Identifier identifier) {
    LOGGER.log(Level.INFO, "getValue(" + identifier + ") called");

    lock.lock();
    try {
      if (cacheHandler.mustRefreshValue(identifier)) {
        return getNewValue(identifier);
      }
      return cacheHandler.getValue(identifier);
    } finally {
      lock.unlock();
      LOGGER.log(Level.INFO, "getValue(" + identifier + ") finished");
    }
  }

  /**
   * Main to test the provider against an aggregation provider.
   *
   * @param args
   */
  public static void main(String[] args) {
    HashMap<Identifier, OBSWParameter> m = new HashMap<Identifier, OBSWParameter>();
    OBSWParameter p = new OBSWParameter(new Long(1), "test_param", "desc", "float", "n/a");
    Identifier id = new Identifier("test_param");
    m.put(id, p);

    NanomindParameterValuesProvider provider = new NanomindParameterValuesProvider(m);

    for (int i = 0; i < 5; i++) {
      Timer timer = new Timer(true);
      timer.schedule(new TimerTask() {
        @Override
        public void run() {
          System.out.println(provider.getValue(id));
        }
      }, 500, 500);
    }

    try {
      while (true) {
        Thread.sleep(500);
      }

    } catch (InterruptedException e) {
      // Auto-generated catch block
    }
  }
}