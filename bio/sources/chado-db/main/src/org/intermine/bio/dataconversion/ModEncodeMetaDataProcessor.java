package org.intermine.bio.dataconversion;

/*
 * Copyright (C) 2002-2009 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.intermine.bio.util.OrganismRepository;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.util.StringUtil;
import org.intermine.xml.full.Attribute;
import org.intermine.xml.full.Item;
import org.intermine.xml.full.Reference;
import org.intermine.xml.full.ReferenceList;

/**
 * Create items from the modENCODE metadata extensions to the chado schema.
 * @author Kim Rutherford, sergio contrino
 */
public class ModEncodeMetaDataProcessor extends ChadoProcessor
{
    private static final Logger LOG = Logger.getLogger(ModEncodeMetaDataProcessor.class);

    // maps to link chado identifiers with intermineObjectId (Integer, Integer)
    // and chado identifiers with item identifiers (Integer, String)
    private Map<Integer, Integer> protocolIdMap = new HashMap<Integer, Integer>();
    private Map<Integer, String> protocolIdRefMap = new HashMap<Integer, String>();
    private Map<Integer, Integer> appliedProtocolIdMap = new HashMap<Integer, Integer>();
    private Map<Integer, String> appliedProtocolIdRefMap = new HashMap<Integer, String>();

    // for projects, the maps link the project name with the identifiers...
    private Map<String, Integer> projectIdMap = new HashMap<String, Integer>();
    private Map<String, String> projectIdRefMap = new HashMap<String, String>();
    // ...we need a further map to link to submission 
    private Map<Integer, String> submissionProjectMap = new HashMap<Integer, String>();

    // for labs, the maps link the lab name with the identifiers...
    private Map<String, Integer> labIdMap = new HashMap<String, Integer>();
    private Map<String, String> labIdRefMap = new HashMap<String, String>();
    // ...we need a further map to link to submission 
    private Map<Integer, String> submissionLabMap = new HashMap<Integer, String>();


    // for labs, the maps link the experimental factor name with the identifiers...
    private Map<String, Integer> eFactorIdMap = new HashMap<String, Integer>();
    private Map<String, String> eFactorIdRefMap = new HashMap<String, String>();
    // ...we need a further map to link to submission 
    private Map<Integer, List<String>> submissionEFactorMap = new HashMap<Integer, List<String>>();

    
    
    //  private Map<String, Integer> organismIdMap = new HashMap<String, Integer>();
//  private Map<String, String> organismIdRefMap = new HashMap<String, String>();
    private Map<Integer, String> submissionOrganismMap = new HashMap<Integer, String>();

    // maps from chado identifier to specific objects
    private Map<Integer, SubmissionDetails> submissionMap =
        new HashMap<Integer, SubmissionDetails>();
    private Map<Integer, AppliedProtocol> appliedProtocolMap =
        new HashMap<Integer, AppliedProtocol>();
    private Map<Integer, AppliedData> appliedDataMap =
        new HashMap<Integer, AppliedData>();

    // list of firstAppliedProtocols, first level of the DAG linking
    // the applied protocols through the data (and giving the flow
    // of data)
    private List<Integer> firstAppliedProtocols = new ArrayList<Integer>();

    // maps of the initial input data and final output data for a submission
    private Map<Integer, List<Integer>> inputsMap = new HashMap<Integer, List<Integer>>();
    private Map<Integer, List<Integer>> outputsMap =
        new HashMap<Integer, List<Integer>>();

    // map used to store all data relative to a submission
    // submissionId, list of appliedDataIds
    private Map<Integer, List<Integer>> submissionDataMap = new HashMap<Integer, List<Integer>>();

    // maps of each submission input with its (submission) output data and vice versa
    private Map<Integer, List<Integer>> inOutDataMap = new HashMap<Integer, List<Integer>>();
    private Map<Integer, List<Integer>> outInDataMap = new HashMap<Integer, List<Integer>>();

    // just for debugging
    private Map<String, String> debugMap = new HashMap<String, String>(); // itemIdentifier, type


    private static class SubmissionDetails
    {
        // the identifier assigned to Item eg. "0_23"
        private String itemIdentifier;
        // the object id of the stored Item
        private Integer interMineObjectId;
        // the identifier assigned to lab Item for this object
        private String labItemIdentifier;
        // the identifier assigned to organism Item for this object
        private String organismItemIdentifier;
    }

    /**
     * AppliedProtocol class
     * to reconstruct the flow of submission data
     *
     */
    private static class AppliedProtocol
    {
        private Integer submissionId;      // chado
        private Integer protocolId;
        private String itemIdentifier;     // e.g. "0_12"
        private Integer intermineObjectId;
        private Integer step;              // the level in the dag for the AP

        // the output data associated to this applied protocol
        private List<Integer> outputs = new ArrayList<Integer>();
        private List<Integer> inputs = new ArrayList<Integer>();
    }

    /**
     * AppliedData class
     * to reconstruct the flow of submission data
     *
     */
    private static class AppliedData
    {
        private String itemIdentifier;
        private Integer intermineObjectId;
        // the list of applied protocols for which this data item is an input
        private List<Integer> nextAppliedProtocols = new ArrayList<Integer>();
        // the list of applied protocols for which this data item is an output
        private List<Integer> previousAppliedProtocols = new ArrayList<Integer>();
    }

    /**
     * Create a new ChadoProcessor object
     * @param chadoDBConverter the converter that created this Processor
     */
    public ModEncodeMetaDataProcessor(ChadoDBConverter chadoDBConverter) {
        super(chadoDBConverter);
    }

    /**
     * {@inheritDoc}
     * Note:TODO
     * 
     */
    @Override
    public void process(Connection connection) throws Exception {
        long bT; // process start time

        bT = System.currentTimeMillis();
        processProjectTable(connection);
        LOG.info("TIME project " + ":" + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processLabTable(connection);
        LOG.info("TIME lab" + ":   "  + (System.currentTimeMillis() - bT));
        //processLabAttributes(connection);

        bT = System.currentTimeMillis();
        processSubmissionOrganism(connection);
        LOG.info("TIME organism" + ":   " + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processExperimentTable(connection);
        LOG.info("TIME experiment" + ":   " + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processExperimentProps(connection);
        LOG.info("TIME experimentProps" + ":   " + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processEFactor(connection);
        LOG.info("TIME experimentalFactors" + ":   " + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processProtocolTable(connection);
        LOG.info("TIME protocol" + ":   " + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processProtocolAttributes(connection);
        LOG.info("TIME protocol attributes" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processAppliedProtocolTable(connection);
        LOG.info("TIME applied protocol" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processAppliedData(connection);
        LOG.info("TIME applied data" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processAppliedDataAttributes(connection);
        LOG.info("TIME applied data attributes" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processDag(connection);
        LOG.info("TIME DAG" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        processFeatures(connection, submissionMap);
        LOG.info("TIME features" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        // links submission inputs with their respective submission outputs
        // also set the references
        // TODO: check if working
        linksInOut(connection);
        LOG.info("TIME InOut" + ":   "  + (System.currentTimeMillis() - bT));

        // set references
        bT = System.currentTimeMillis();
        setSubmissionRefs(connection);
        LOG.info("TIME setsubmissionRefs" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        setSubmissionInputRefs(connection);
        LOG.info("TIME setsubmission" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        setSubmissionResultsRefs(connection);
        LOG.info("TIME setsubmissionResults" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        setSubmissionProtocolsRefs(connection);
        LOG.info("TIME setsubmissionProtocols" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        setSubmissionEFactorsRefs(connection);
        LOG.info("TIME setSubmissionEFactorsRefs" + ":   "  + (System.currentTimeMillis() - bT));

        bT = System.currentTimeMillis();
        setDAGRefs(connection);
        LOG.info("TIME setDAG" + ":   "  + (System.currentTimeMillis() - bT));
    }


    /**
     *
     * ==============
     *    FEATURES
     * ==============
     *
     * @param connection
     * @param submissionMap
     * @throws Exception
     */
    private void processFeatures(Connection connection,
            Map<Integer, SubmissionDetails> submissionMap)
    throws Exception {

        // hold features that should only be processed once across all submissions, initialise
        // processor with this map each time
        Map <Integer, FeatureData> commonFeaturesMap = new HashMap<Integer, FeatureData>();

        for (Map.Entry<Integer, SubmissionDetails> entry: submissionMap.entrySet()) {

            Map<Integer, FeatureData> subFeatureMap = new HashMap<Integer, FeatureData>();
            Integer chadoExperimentId = entry.getKey();
            SubmissionDetails submissionDetails = entry.getValue();
            String submissionItemIdentifier = submissionDetails.itemIdentifier;
            String labItemIdentifier = submissionDetails.labItemIdentifier;

            List<Integer> thisSubmissionDataIds = submissionDataMap.get(chadoExperimentId);
            LOG.info("DATA IDS " + chadoExperimentId + ": " + thisSubmissionDataIds.size());

            ModEncodeFeatureProcessor processor =
                new ModEncodeFeatureProcessor(getChadoDBConverter(), submissionItemIdentifier,
                        labItemIdentifier, thisSubmissionDataIds);
            processor.initialiseCommonFeatures(commonFeaturesMap);
            processor.process(connection);
            
            // all features related to this submission
            subFeatureMap.putAll(processor.getFeatureMap());
            
            // features common across many submissions
            commonFeaturesMap.putAll(processor.getCommonFeaturesMap());
            LOG.info("COMMON FEATURES: " + commonFeaturesMap.size());

            if (subFeatureMap.keySet().size() == 0) {
                LOG.error("FEATMAP: submission " + chadoExperimentId     
                        + " has no featureMap keys.");
                continue;
            } 
            LOG.debug("FEATMAP: submission " + chadoExperimentId + "|"    
                    + "featureMap: " + subFeatureMap.keySet().size());

            String queryList = StringUtil.join(thisSubmissionDataIds, ",");
            processDataFeatureTable(connection, subFeatureMap, queryList);
        }
    }

    /**    
     * @param connection
     * @param featureMap
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processDataFeatureTable(Connection connection, Map<Integer,
            FeatureData> featureMap, String queryList)
    throws SQLException, ObjectStoreException {
        ResultSet res = getDataFeatureResultSet(connection, queryList);

        Integer prevId = 0;    // previous id in the loop
        Integer collectionSize = 0;
        ReferenceList collection = null;
      
        while (res.next()) {
            Integer dataId = new Integer(res.getInt("data_id"));
            Integer featureId = new Integer(res.getInt("feature_id"));
            FeatureData featureData = featureMap.get(featureId);
            if (featureData == null) {
                LOG.error("FIXME: no data for feature_id: " + featureId
                        + " in processDataFeatureTable(), data_id =" + dataId);
                continue;
            }

            // get feature data
            String featureItemId = featureData.getItemIdentifier();
            LOG.debug("dataId " + prevId + " -> " + dataId + " FD "
                    + featureData.getInterMineType() + ": " 
                    + featureData.getChadoFeatureName());

            if (!dataId.equals(prevId)) {
                if (prevId != 0) {
                    // store previous collection
                    LOG.info("STORING collection for dataId " + prevId + ": " 
                            + appliedDataMap.get(prevId).intermineObjectId 
                            + " size:" + collectionSize); 
                    getChadoDBConverter().store(collection, 
                            appliedDataMap.get(prevId).intermineObjectId);                    
                }
                collection = new ReferenceList();
                collection.setName("features");
                collection.addRefId(featureItemId);
                collectionSize++;
            }

            if (dataId.equals(prevId)) {
                collection.addRefId(featureItemId);
                collectionSize++;                
                LOG.debug("dataId ADDREF " + dataId + "|" + featureItemId + "|" + collectionSize);
            } 

            if (res.isLast()) {
                LOG.info("STORING collection for dataId " + dataId + ": " 
                        + " size:" + collectionSize); 
                getChadoDBConverter().store(collection, 
                        appliedDataMap.get(dataId).intermineObjectId);
            }
            prevId = dataId;
        }
    }

// TODO: remove
    private void processDataFeatureTableOld(Connection connection, Map<Integer,
            FeatureData> featureMap, String queryList)
    throws SQLException, ObjectStoreException {
        ResultSet res = getDataFeatureResultSet(connection, queryList);

        ReferenceList collection = new ReferenceList();
        collection.setName("features");

        Integer id = 0;
        Integer collectionSize = 0;
        while (res.next()) {
            Integer dataId = new Integer(res.getInt("data_id"));
            Integer featureId = new Integer(res.getInt("feature_id"));
            FeatureData featureData = featureMap.get(featureId);
            if (featureData == null) {
                LOG.error("FIXME: no data for feature_id: " + featureId
                        + " in processDataFeatureTable(), data_id =" + dataId);
                continue;
            }
            id = dataId;
            String featureItemId = featureData.getItemIdentifier();

            // old ref setting
//          Reference featureRef = new Reference("feature", featureItemId);
//          getChadoDBConverter().store(featureRef,
//          appliedDataMap.get(dataId).intermineObjectId);

            collection.addRefId(featureItemId);
            collectionSize++;
        }
        getChadoDBConverter().store(collection, appliedDataMap.get(id).intermineObjectId);
        collectionSize = 0;
    }

    private ResultSet getDataFeatureResultSet(Connection connection, String queryList)
    throws SQLException {
        String query =
            "SELECT df.data_id, df.feature_id"
            + " FROM data_feature df "
            + " WHERE data_id in (" + queryList + ")";

        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }



    /**
     *
     * ====================
     *         DAG
     * ====================
     *
     * In chado, Applied protocols in a submission are linked to each other via
     * the flow of data (output of a parent AP are input to a child AP).
     * The method process the data from chado to build the objects
     * (SubmissionDetails, AppliedProtocol, AppliedData) and their
     * respective maps to chado identifiers needed to traverse the DAG.
     * It then traverse the DAG, assigning the experiment_id to all data.
     *
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processDag(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getDAGResultSet(connection);
        AppliedProtocol node = new AppliedProtocol();
        AppliedData branch = null;
        Integer count = 0;
        Integer actualSubmissionId = 0;  // to store the experiment id (see below)

        Integer previousAppliedProtocolId = 0;
        while (res.next()) {
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            Integer protocolId = new Integer(res.getInt("protocol_id"));
            Integer appliedProtocolId = new Integer(res.getInt("applied_protocol_id"));
//          Integer appliedDataId = new Integer(res.getInt("applied_protocol_data_id"));
            Integer dataId = new Integer(res.getInt("data_id"));
            String direction = res.getString("direction");

            // build a data node for each iteration
            if (appliedDataMap.containsKey(dataId)) {
                branch = appliedDataMap.get(dataId);
            } else {
                branch = new AppliedData();
            }
            // could use > (order by apid, apdataid, direction)
            // NB: using isLast() is expensive
            if (!appliedProtocolId.equals(previousAppliedProtocolId) || res.isLast()) {

                // the submissionId != null for the first applied protocol
                if (submissionId > 0) {
                    firstAppliedProtocols.add(appliedProtocolId);
                    if (direction.startsWith("in")) {
                        // .. the map of initial data for the submission
                        addToMap (inputsMap, submissionId, dataId);
                        addToMap (submissionDataMap, submissionId, dataId);
                    }
                    // and set actual submission id
                    // we can either be at a first applied protocol (submissionId > 0)..
                    actualSubmissionId = submissionId;
                } else {
                    // ..or already down the dag, and we use the stored id. 
                    submissionId = actualSubmissionId;
                }

                // last one: fill the list of outputs 
                // and add to the general list of data ids for the submission, 
                // used to fetch features
                if (res.isLast()) {
                    if (direction.equalsIgnoreCase("output")) {
                        node.outputs.add(dataId);
                        addToMap (submissionDataMap, submissionId, dataId);
                    }
                }

                // if it is not the first iteration, let's store it
                if (previousAppliedProtocolId > 0) {
                    appliedProtocolMap.put(previousAppliedProtocolId, node);
                }

                // new node
                AppliedProtocol newNode = new AppliedProtocol();
                newNode.protocolId = protocolId;                
                newNode.submissionId = submissionId;

                if (direction.startsWith("in")) {
                    // add this applied protocol to the list of nextAppliedProtocols
                    branch.nextAppliedProtocols.add(appliedProtocolId);
                    // ..and update the map
                    if (appliedDataMap.containsKey(dataId)) {
                        appliedDataMap.remove(dataId);
                    }
                    appliedDataMap.put(dataId, branch);
                    // .. and add the dataId to the list of input Data for this applied protocol
                    newNode.inputs.add(dataId);
                } else if (direction.startsWith("out")) {
                    // add the dataId to the list of output Data for this applied protocol:
                    // it will be used to link to the next set of applied protocols
                    newNode.outputs.add(dataId);
                } else {
                    // there is some problem with the strings 'input' or 'output'
                    throw new IllegalArgumentException("Data direction not valid for dataId: "
                            + dataId + "|" + direction + "|");
                }
                // for the new round..
                node = newNode;
                previousAppliedProtocolId = appliedProtocolId;
            } else {
                // keep feeding IN et OUT
                if (direction.startsWith("in")) {
                    node.inputs.add(dataId);
                    if (submissionId > 0) {
                        // initial data
                        addToMap (submissionDataMap, submissionId, dataId);
                        addToMap (inputsMap, submissionId, dataId);
                    }
                    // as above
                    branch.nextAppliedProtocols.add(appliedProtocolId);
                    if (!appliedDataMap.containsKey(dataId)) {
                        appliedDataMap.put(dataId, branch);
                    } else {
                        appliedDataMap.remove(dataId);
                        appliedDataMap.put(dataId, branch);
                    }
                } else if (direction.startsWith("out")) {
                    node.outputs.add(dataId);
                } else {
                    throw new IllegalArgumentException("Data direction not valid for dataId: "
                            + dataId + "|" + direction + "|");
                }
            }
            count++;
        }
        LOG.info("created " + appliedProtocolMap.size() 
                + "(" + count + " applied data points) DAG nodes (= applied protocols) in map");

        res.close();

        // now traverse the DAG, and associate submission with all the applied protocols
        traverseDag();
        // set the dag level as an attribute to applied protocol
        setAppliedProtocolSteps(connection);
    }

    /**
     * 
     * to set the step attribute for the applied protocols
     * 
     */
    private void setAppliedProtocolSteps(Connection connection)
    throws ObjectStoreException {
        Iterator<Integer> ap = appliedProtocolMap.keySet().iterator();
        while (ap.hasNext()) {
            Integer thisAPId = ap.next();
            Attribute attr = new Attribute();
            attr.setName("step");
            attr.setValue(appliedProtocolMap.get(thisAPId).step.toString());

            getChadoDBConverter().store(attr,
                    appliedProtocolIdMap.get(thisAPId));
        }
    }


    /**
     * Return the rows needed to construct the DAG of the data/protocols.
     * The reference to the submission is available only for the first set
     * of applied protocols, hence the outer join.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getDAGResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT eap.experiment_id, ap.protocol_id, apd.applied_protocol_id"
            + " , apd.data_id, apd.applied_protocol_data_id, apd.direction"
            + " FROM applied_protocol ap LEFT JOIN experiment_applied_protocol eap"
            + " ON (eap.first_applied_protocol_id = ap.applied_protocol_id )"
            + " , applied_protocol_data apd"
            + " WHERE apd.applied_protocol_id = ap.applied_protocol_id"
            + " ORDER By 3,5,6";

        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    /**
     * Applies iteratively buildADaglevel
     *
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void traverseDag()
    throws SQLException, ObjectStoreException {
        List<Integer> currentIterationAP = firstAppliedProtocols;
        List<Integer> nextIterationAP = new ArrayList<Integer>();
        Integer step = 1; // DAG level

        while (currentIterationAP.size() > 0) {
            nextIterationAP = buildADagLevel (currentIterationAP, step);
            currentIterationAP = nextIterationAP;
            step++;
        }
    }

    /**
     * This method is given a set of applied protocols (already associated with a submission)
     * and produces the next set of applied protocols. The latter are the protocols attached to the
     * output data of the starting set (output data for a applied protocol is the input data for the
     * next one).
     * It also fills the map linking directly results ('leaf' output data) with submission
     *
     * @param previousAppliedProtocols
     * @return the next batch of appliedProtocolId
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private List<Integer> buildADagLevel(List<Integer> previousAppliedProtocols, Integer step)
    throws SQLException, ObjectStoreException {
        List<Integer> nextIterationProtocols = new ArrayList<Integer>();
        Iterator<Integer> pap = previousAppliedProtocols.iterator();
        while (pap.hasNext()) {
            List<Integer> outputs = new ArrayList<Integer>();
            Integer currentId = pap.next();

            // add the DAG level here only if these are the first AP
            if (step == 1) {
                appliedProtocolMap.get(currentId).step = step;
            }

            outputs.addAll(appliedProtocolMap.get(currentId).outputs);
            Integer submissionId = appliedProtocolMap.get(currentId).submissionId;
            Iterator<Integer> od = outputs.iterator();
            while (od.hasNext()) {
                Integer currentOD = od.next();
                List<Integer> nextProtocols = new ArrayList<Integer>();
                // build map submission-data
                addToMap (submissionDataMap, submissionId, currentOD);
                if (appliedDataMap.containsKey(currentOD)) {
                    // fill the list of next (children) protocols
                    nextProtocols.addAll(appliedDataMap.get(currentOD).nextAppliedProtocols);
                    if (appliedDataMap.get(currentOD).nextAppliedProtocols.isEmpty()) {
                        // this is a leaf!!
                        // we store it in a map that links it directly to the submission
                        addToMap(outputsMap, submissionId, currentOD);
                    }
                }

                // build the list of children applied protocols chado identifiers
                // as input for the next iteration
                Iterator<Integer> nap = nextProtocols.iterator();
                while (nap.hasNext()) {
                    Integer currentAPId = nap.next();
                    // and fill the map with the chado experiment_id
                    // and the DAG level
                    appliedProtocolMap.get(currentAPId).submissionId = submissionId;
                    appliedProtocolMap.get(currentAPId).step = step + 1;

                    nextIterationProtocols.add(currentAPId);

                    // and set the reference from applied protocol to the submission
                    Reference reference = new Reference();
                    reference.setName("submission");
                    reference.setRefId(submissionMap.get(submissionId).itemIdentifier);
                    getChadoDBConverter().store(reference, appliedProtocolIdMap.get(currentAPId));
                }
            }
        }
        return nextIterationProtocols;
    }

    /**
     *
     * =======================
     *    SUBMISSION IN-OUT
     * =======================
     *
     *    This part of the code deals with setting references between
     *    each submission input (inputs) with its respective
     *    submission output(s) (outputs), black-boxing the DAG.
     *    This method loop through all the submissions, each time
     *    creating the maps linking ins and outs and then setting
     *    the references.
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void linksInOut(Connection connection)
    throws SQLException, ObjectStoreException {
        Set <Integer> submissions = inputsMap.keySet();
        Iterator <Integer> thisSubmission = submissions.iterator();

        while (thisSubmission.hasNext()) {
            // TODO: use local maps?
            // FOR EACH submission
            // clear the maps
            inOutDataMap.clear();
            outInDataMap.clear();

            // build In-Out map
            buildInOutMap (thisSubmission.next());
            // the reverse map is built from the previous one
            buildOutInMap();

            setInOutRefs(connection);
            setOutInRefs(connection);
        }
    }


    /**
     * Builds the map linking each input to its outputs
     *
     * @param submissionId
     */
    private void buildInOutMap(Integer submissionId) {
        // get all the submission input data
        List <Integer> inputs = inputsMap.get(submissionId);
        Iterator <Integer> thisInput = inputs.iterator();

        while (thisInput.hasNext()) {
            Integer currentId = thisInput.next();
            // for each find the related outputs.
            // note: the first time only the submission input is sent to the loop,
            // then it could be list of intermediate outputs (which are
            // inputs to other applied protocols) that need to be processed
            // (i.e. used to find the their respective outputs).
            List <Integer> currentIteration = new ArrayList<Integer>();
            List<Integer> nextIteration = new ArrayList<Integer>();
            currentIteration.add(currentId);

            while (currentIteration.size() > 0) {
                nextIteration = getOutputs (currentIteration, currentId);
                currentIteration = nextIteration;
            }
        }
    }


    /**
     * get the outputs related to a list of inputs and builds the inOutData map.
     * note: for each submission input, when this is first called the list contains
     * only the submission input. Then it is filled with the outputs found (that are
     * potentially inputs to following steps).
     *
     * @param ids list of intermediate inputs (they are outputs of previous steps)
     * @param submissionInput  the initial input we are considering
     * @return the list of outputs related to the list of inputs
     */
    private List<Integer> getOutputs(List<Integer> ids, Integer submissionInput) {
        // actually this method also set the map.
        List <Integer> outs = new ArrayList<Integer>();

        Iterator <Integer> dataId = ids.iterator();
        while (dataId.hasNext()) {
            Integer currentIn = dataId.next();
            List <Integer> nextAppliedProtocols =
                appliedDataMap.get(currentIn).nextAppliedProtocols;

            if (nextAppliedProtocols.isEmpty()) {
                // this is a submission output: let's connect it to the
                // submission input
                addToMap(inOutDataMap, submissionInput, currentIn);
            } else {
                // keep gathering the outputs..
                Iterator <Integer> nap = nextAppliedProtocols.iterator();
                while (nap.hasNext()) {
                    // addToList checks if the various ids are already present in
                    // the results list before adding them
                    addToList(outs, appliedProtocolMap.get(nap.next()).outputs);
                }
            }
        }
        return outs;
    }

    /**
     * builds the map from each submission output to its related inputs.
     * it uses the previously built reverse map (each submission input
     * with its related submission output(s))
     *
     */
    private void buildOutInMap() {
        Set <Integer> in = inOutDataMap.keySet();
        Iterator <Integer> ins = in.iterator();
        while (ins.hasNext()) {
            Integer thisIn = ins.next();
            List <Integer> out = inOutDataMap.get(thisIn);
            Iterator <Integer> outs = out.iterator();
            while (outs.hasNext()) {
                addToMap (outInDataMap, outs.next(), thisIn);
            }
        }
    }


    /**
     *
     * ==============
     *    PROJECT
     * ==============
     *
     * Projects are loaded statically. A map is built between submissionId and
     * project's name and used for the references. 2 maps store intermine
     * objectId and itemId, with key the project name.
     * Note: the project name in chado is the surname of the PI
     * 
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processProjectTable(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getProjectResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            String value = res.getString("value");
            submissionProjectMap.put(submissionId, value);
            count++;
        }
        res.close();

        Set <Integer> exp = submissionProjectMap.keySet();
        Iterator <Integer> i  = exp.iterator();
        while (i.hasNext()) {
            Integer thisExp = i.next();
            String project = submissionProjectMap.get(thisExp);  

            if (projectIdMap.containsKey(project)) {
                continue;
            }
            LOG.debug("PROJECT: " + project);            
            Item pro = getChadoDBConverter().createItem("Project");
            pro.setAttribute("surnamePI", project);
            Integer intermineObjectId = getChadoDBConverter().store(pro);
            storeInProjectMaps(pro, project, intermineObjectId);
        }
        LOG.info("created " + projectIdMap.size() + " project");
    }

    /**
     * Return the project name.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getProjectResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT distinct a.experiment_id, a.value "
            + " FROM experiment_prop a "
            + " where a.name = 'Project'";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }


    /**
     *
     * ==============
     *    LAB
     * ==============
     *
     * Labs are also loaded statically (affiliation is not given in the chado file).
     * A map is built between submissionId and
     * lab's name and used for the references. 2 maps store intermine
     * objectId and itemId, with key the lab name.
     * TODO: do project and lab together (1 query, 1 process)
     * 
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processLabTable(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getLabResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            String value = res.getString("value");
            submissionLabMap.put(submissionId, value);
            count++;
        }
        res.close();

        Set <Integer> exp = submissionLabMap.keySet();
        Iterator <Integer> i  = exp.iterator();
        while (i.hasNext()) {
            Integer thisExp = i.next();
            String prov = submissionLabMap.get(thisExp);  
            String project = submissionProjectMap.get(thisExp);  

            if (labIdMap.containsKey(prov)) {
                continue;
            }
            LOG.debug("PROV: " + prov);            
            Item lab = getChadoDBConverter().createItem("Lab");
//            lab.setAttribute("name", prov);
            lab.setAttribute("surname", prov);
            lab.setReference("project", projectIdRefMap.get(project));
            
            Integer intermineObjectId = getChadoDBConverter().store(lab);
            storeInLabMaps(lab, prov, intermineObjectId);
        }
        LOG.info("created " + labIdMap.size() + " labs");
    }

    /**
     * Return the lab name.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getLabResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT distinct a.experiment_id, a.name, a.value "
            + " FROM experiment_prop a "
            + " where a.name = 'Lab'";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    /**
     * to store lab attributes
     * 
     * NOTE: Not used now. 
     * TODO: to remove
     * 
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processLabAttributes(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getLabAttributesResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            String heading = res.getString("name");
            String value = res.getString("value");
            String fieldName = PROVIDER_FIELD_NAME_MAP.get(heading);
            if (fieldName == null) {
                LOG.error("NOT FOUND in PROVIDER_FIELD_NAME_MAP: " + heading);
                continue;
            } else if (fieldName == NOT_TO_BE_LOADED) {
                continue;
            }
            setAttribute(labIdMap.get(submissionId), fieldName, value);
            count++;
        }
        LOG.info("created " + count + " lab properties");
        res.close();
    }

    /**
     * Return the rows needed for lab from the lab_prop table.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */

    protected ResultSet getLabAttributesResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT experiment_id, name, value"
            + " FROM experiment_prop"
            + " where rank=0 ";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }


    /**
     *
     * ==============
     *    ORGANISM
     * ==============
     *
     * Organism for a submission is derived from the organism associated with
     * the protocol of the first applied protocol (of the submission).
     * it is the name. a request to associate the submission directly with
     * the taxonid has been made to chado people.
     * 
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processSubmissionOrganism(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getSubmissionOrganism(connection);
        int count = 0;
        while (res.next()) {
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            String value = res.getString("value");
            submissionOrganismMap.put(submissionId, value);
            count++;
        }
        res.close();
        LOG.info("created " + submissionOrganismMap.size() + " organisms");
    }


    /**
     * Return the rows needed from the lab table.
     * We use the surname of the Principal Investigator (person ranked 0)
     * as the lab name.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getSubmissionOrganism(Connection connection)
    throws SQLException {
        String query =
            "select distinct eap.experiment_id, a.value "
            + " from experiment_applied_protocol eap, applied_protocol ap, "
            + " protocol_attribute pa, attribute a "
            + " where eap.first_applied_protocol_id = ap.applied_protocol_id "
            + " and ap.protocol_id=pa.protocol_id "
            + " and pa.attribute_id=a.attribute_id "
            + " and a.heading='species' ";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }


    /**
     *
     * ================
     *    SUBMISSION
     * ================
     *
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processExperimentTable(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getExperimentResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            String name = res.getString("uniquename");
            Item submission = getChadoDBConverter().createItem("Submission");
            submission.setAttribute("title", name);

            String project = submissionProjectMap.get(submissionId);
            String projectItemIdentifier = projectIdRefMap.get(project);
            submission.setReference("project", projectItemIdentifier);

            String labName = submissionLabMap.get(submissionId);
            String labItemIdentifier = labIdRefMap.get(labName);
            submission.setReference("lab", labItemIdentifier);

            String organismName = submissionOrganismMap.get(submissionId);

            int divPos = organismName.indexOf(' ');
            String genus = organismName.substring(0, divPos);
            String species = organismName.substring(divPos + 1);

            OrganismRepository or = OrganismRepository.getOrganismRepository();

            Integer taxId = Integer.valueOf
            (or.getOrganismDataByGenusSpecies(genus, species).getTaxonId());

            LOG.debug("SPECIES: " + organismName + "|" + taxId);            

            String organismItemIdentifier = getChadoDBConverter().getOrganismItem
            (or.getOrganismDataByGenusSpecies(genus, species).getTaxonId()).getIdentifier();

            submission.setReference("organism", organismItemIdentifier);

            // ..store all
            Integer intermineObjectId = getChadoDBConverter().store(submission);
            // ..and fill the SubmissionDetails object
            SubmissionDetails details = new SubmissionDetails();
            details.interMineObjectId = intermineObjectId;
            details.itemIdentifier = submission.getIdentifier();
            details.labItemIdentifier = labItemIdentifier;
            details.organismItemIdentifier = organismItemIdentifier;
            submissionMap.put(submissionId, details);

            debugMap .put(details.itemIdentifier, submission.getClassName());
            count++;
        }
        LOG.info("created " + count + " submissions");
        res.close();
    }

    /**
     * Return the rows needed for the submission table.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getExperimentResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT experiment_id, uniquename"
            + "  FROM experiment";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    /**
     * experiment attributes
     *
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processExperimentProps(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getExperimentPropResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            String heading = res.getString("name");
            String value = res.getString("value");
            int rank = res.getInt("rank");

            // TODO this is a temporary hack to make sure we get properly matched Experiment.factors
            if (heading.startsWith("Experimental Factor") && (rank != 0)) {
                continue;
            }
            
            String fieldName = FIELD_NAME_MAP.get(heading);
            if (fieldName == null) {
                LOG.error("NOT FOUND in FIELD_NAME_MAP: " + heading + " [experiment]");
                continue;
            } else if (fieldName == NOT_TO_BE_LOADED) {
                continue;
            }
            setAttribute(submissionMap.get(submissionId).interMineObjectId, fieldName, value);
            count++;
        }
        LOG.info("created " + count + " submission properties");
        res.close();
    }

    /**
     * Return the rows needed for submission from the experiment_prop table.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getExperimentPropResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT ep.experiment_id, ep.name, ep.value, ep.rank "
            + "from experiment_prop ep ";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    
    /**
    *
    * ==========================
    *    EXPERIMENTAL FACTORS
    * ==========================
    *
    * Labs are also loaded statically (affiliation is not given in the chado file).
    * A map is built between submissionId and
    * lab's name and used for the references. 2 maps store intermine
    * objectId and itemId, with key the lab name.
    * TODO: do project and lab together (1 query, 1 process)
    * 
    * @param connection
    * @throws SQLException
    * @throws ObjectStoreException
    */
   private void processEFactor(Connection connection)
   throws SQLException, ObjectStoreException {
       ResultSet res = getEFactorResultSet(connection);
       int count = 0;
       int prevRank = -1;
       int prevSub = -1;
       String efName = null;
//       List<String> subEFs = new ArrayList<String>();
       
       while (res.next()) {
           Integer submissionId = new Integer(res.getInt("experiment_id"));
           Integer rank = new Integer(res.getInt("rank"));
           String  whichOne = res.getString("name");
           String  value    = res.getString("value");   
                     
           if (rank != prevRank || submissionId != prevSub) {
               efName = value;
               LOG.info("EF " + rank + "|" + prevRank + "->" + efName);
           } else {

               if (!eFactorIdMap.containsKey(efName)) {
               Item ef = getChadoDBConverter().createItem("ExperimentalFactor");
                   ef.setAttribute ("name", efName);
                   ef.setAttribute ("type", value);

                   LOG.info("EF " + "type: " + value + " (" + submissionId + ")");

                   Integer intermineObjectId = getChadoDBConverter().store(ef);
//                   storeInProtocolMaps (protocol, protocolId, intermineObjectId);
                   eFactorIdMap .put(efName, intermineObjectId);
                   eFactorIdRefMap .put(efName, ef.getIdentifier());
               }
                   
                   addToMap(submissionEFactorMap, submissionId, efName);

                   LOG.info("EF MAP " + submissionEFactorMap);
                    
                   count++;
               }
           prevRank = rank;
           prevSub = submissionId;
       }
       res.close();
       LOG.info("EF MAP id  " + eFactorIdMap);
       LOG.info("EF MAP ref " + eFactorIdRefMap);

       LOG.info("created " + eFactorIdMap.size() + " experimental factors");
   }

   /**
    * Return the rows needed from the lab*** table.
    * This is a protected method so that it can be overridden for testing
    *
    * @param connection the db connection
    * @return the SQL result set
    * @throws SQLException if a database problem occurs
    */
   protected ResultSet getEFactorResultSet(Connection connection)
   throws SQLException {
       String query =
           "SELECT ep.experiment_id, ep.name, ep.value, ep.rank "
           + " FROM experiment_prop ep "
           + " where ep.name = 'Experimental Factor Name' "
           + " OR ep.name = 'Experimental Factor Type' "
           + " ORDER BY 1,4,2";
       LOG.info("executing: " + query);
       Statement stmt = connection.createStatement();
       ResultSet res = stmt.executeQuery(query);
       return res;
   }

    
    /**
     *
     * ==============
     *    PROTOCOL
     * ==============
     *
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processProtocolTable(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getProtocolResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer protocolId = new Integer(res.getInt("protocol_id"));
            String name = res.getString("name");
            String description = res.getString("description");
            // needed: it breaks otherwise
            if (description.length() == 0) {
                description = "N/A";
            }
            Item protocol = getChadoDBConverter().createItem("Protocol");
            protocol.setAttribute("name", name);
            protocol.setAttribute("description", description);
            Integer intermineObjectId = getChadoDBConverter().store(protocol);
            storeInProtocolMaps (protocol, protocolId, intermineObjectId);
            //protocolIdMap .put(protocolId, intermineObjectId);
            //protocolIdRefMap .put(protocolId, protocol.getIdentifier());
            count++;
        }
        res.close();
        LOG.info("created " + count + " protocols");
    }

    /**
     * Return the rows needed from the protocol table.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getProtocolResultSet(Connection connection) throws SQLException {
        String query =
            "SELECT protocol_id, name, description"
            + "  FROM protocol";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    /**
     * to store protocol attributes
     *
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processProtocolAttributes(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getProtocolAttributesResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer protocolId = new Integer(res.getInt("protocol_id"));
            String heading = res.getString("heading");
            String value = res.getString("value");
            String fieldName = FIELD_NAME_MAP.get(heading);
            if (fieldName == null) {
                LOG.error("NOT FOUND in FIELD_NAME_MAP: " + heading + " [protocol]");
                continue;
            } else if (fieldName == NOT_TO_BE_LOADED) {
                continue;
            }
            setAttribute(protocolIdMap.get(protocolId), fieldName, value);
            count++;
        }
        LOG.info("created " + count + " protocol attributes");
        res.close();
    }

    /**
     * Return the rows needed for protocols from the attribute table.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getProtocolAttributesResultSet(Connection connection) throws SQLException {
        String query =
            "SELECT p.protocol_id, a.heading, a.value "
            + "from protocol p, attribute a, protocol_attribute pa "
            + "where pa.attribute_id = a.attribute_id "
            + "and pa.protocol_id = p.protocol_id ";
        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    /**
     * ======================
     *    APPLIED PROTOCOL
     * ======================
     *
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processAppliedProtocolTable(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getAppliedProtocolResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer appliedProtocolId = new Integer(res.getInt("applied_protocol_id"));
            Integer protocolId = new Integer(res.getInt("protocol_id"));
            Integer submissionId = new Integer(res.getInt("experiment_id"));
            Item appliedProtocol = getChadoDBConverter().createItem("AppliedProtocol");
            // setting references to protocols
            appliedProtocol.setReference("protocol", protocolIdRefMap.get(protocolId));
            if (submissionId > 0) {
                // setting reference to submission
                // probably to rm (we do it later anyway). TODO: check
                appliedProtocol.setReference("submission",
                        submissionMap.get(submissionId).itemIdentifier);
            }
            // store it and add to maps
            Integer intermineObjectId = getChadoDBConverter().store(appliedProtocol);
            appliedProtocolIdMap .put(appliedProtocolId, intermineObjectId);
            appliedProtocolIdRefMap .put(appliedProtocolId, appliedProtocol.getIdentifier());
            count++;
        }
        LOG.info("created " + count + " appliedProtocol");
        res.close();
    }

    /**
     * Return the rows needed from the appliedProtocol table.
     * This is a protected method so that it can be overridden for testing
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getAppliedProtocolResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT eap.experiment_id ,ap.applied_protocol_id, ap.protocol_id"
            + " FROM applied_protocol ap"
            + " LEFT JOIN experiment_applied_protocol eap"
            + " ON (eap.first_applied_protocol_id = ap.applied_protocol_id )";

        /*        "SELECT ap.applied_protocol_id, ap.protocol_id, apd.data_id, apd.direction"
        + " FROM applied_protocol ap, applied_protocol_data apd"
        + " WHERE apd.applied_protocol_id = ap.applied_protocol_id";
         */

        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }


    /**
     * ======================
     *    APPLIED DATA
     * ======================
     * 
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */

    private void processAppliedData(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getAppliedDataResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer dataId = new Integer(res.getInt("data_id"));
            String name = res.getString("name");
            String heading = res.getString("heading");
            String value = null;
            
            // check if this datum has an official name:
            ResultSet oName = getOfficialName(connection, dataId); 
            String officialName = null;            
            while (oName.next()) {
                officialName = oName.getString(1);
            }

            // if there is one, use it instead of the value
            if (!StringUtils.isEmpty(officialName)){
                value = officialName;
            } else {
                value = res.getString("value");
            }
            
            Item submissionData = getChadoDBConverter().createItem("SubmissionData");
            
            if (name != null && !name.equals("")) {
                submissionData.setAttribute("name", name);
            }
            if ((name == null || name.equals("")) && heading != null && heading.contains(" Name")) {
                submissionData.setAttribute("name", heading.replace(" Name", ""));
            }
            if (!StringUtils.isEmpty(value)) {
                submissionData.setAttribute("value", value);
            }
            submissionData.setAttribute("type", heading);

            // store it and add to object and maps
            Integer intermineObjectId = getChadoDBConverter().store(submissionData);

            AppliedData aData = new AppliedData();
            aData.intermineObjectId = intermineObjectId;
            aData.itemIdentifier = submissionData.getIdentifier();
            appliedDataMap.put(dataId, aData);

            LOG.debug("ADMAP put: data_id " + dataId + "|" + intermineObjectId + " imobjid");
            count++;
        }
        LOG.info("created " + count + " SubmissionData");
        res.close();
    }

    /**
     * Return the rows needed for data from the applied_protocol_data table.
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getAppliedDataResultSet(Connection connection)
    throws SQLException {
        String query =
            "SELECT d.data_id,"
            + " d.heading, d.name, d.value"
            + " FROM data d";

        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    
    /**
     * Return the rows needed for data from the applied_protocol_data table.
     *
     * @param connection the db connection
     * @param dataId the dataId
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getOfficialName(Connection connection, Integer dataId)
    throws SQLException {
        String query =
            "SELECT a.value " 
            + " from attribute a, data_attribute da "
            + " where a.attribute_id=da.attribute_id "
            + " and da.data_id=" + dataId
            + " and a.heading='official name'";

        // LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
       
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    /*
     * an attempt to get data and attribute all together
     * it's bugged
     * 
     */
    private void processAppliedDataBad(Connection connection)
    throws SQLException, ObjectStoreException {
        Long bT = System.currentTimeMillis();        
        ResultSet res = getAppliedDataResultSetAll(connection);
        LOG.info("TIME applied data QUERY" + ":   "  + (System.currentTimeMillis() - bT));

        String value = null;
        String name = null;
        String heading = null;

        int count = 0;
        Integer prevDataId=-1;
        Item prevSubmissionData = null;

        while (res.next()) {
            Item submissionData = null;
            Integer dataId = new Integer(res.getInt("data_id"));

            if (dataId != prevDataId){
                submissionData = getChadoDBConverter().createItem("SubmissionData");
                if (prevDataId>-1){
                    // store previous item and add to object and maps
                    Integer intermineObjectId = getChadoDBConverter().store(prevSubmissionData);

                    AppliedData aData = new AppliedData();
                    aData.intermineObjectId = intermineObjectId;
                    aData.itemIdentifier = prevSubmissionData.getIdentifier();
                    appliedDataMap.put(dataId, aData);

                    LOG.debug("ADMAP put: data_id " + dataId + "|" + intermineObjectId + " imobjid");
                } 

                // new item                
                name = res.getString("name");
                value = res.getString("value");
                heading = res.getString("heading");
                LOG.info("DADEBUG 2: " + heading+name+value);

                if (name != null && !name.equals("")) {
                    submissionData.setAttribute("name", name);
                }
                if ((name == null || name.equals("")) && heading!= null && heading.contains(" Name")) {
                    submissionData.setAttribute("name", heading.replace(" Name", ""));
                }
                if (!StringUtils.isEmpty(value)) {
                    submissionData.setAttribute("value", value);
                }
                submissionData.setAttribute("type", heading);
            }

            String aHeading = res.getString("aHeading");
            if (!StringUtils.isEmpty(aHeading)){
                String aName = res.getString("aName");
                String aValue = res.getString("aValue");
                Item dataAttribute = getChadoDBConverter().createItem("SubmissionDataAttribute");
                if (aName != null && !aName.equals("")) {
                    dataAttribute.setAttribute("name", aName);
                }
                if (!StringUtils.isEmpty(aValue)) {
                    dataAttribute.setAttribute("value", aValue);
                }
                dataAttribute.setAttribute("type", aHeading);

                // setting references to dataSubmission
                dataAttribute.setReference("submissionData", submissionData.getIdentifier());
                // store it and add to object and maps
                Integer intermineObjectId = getChadoDBConverter().store(dataAttribute);

                if (aHeading == "official name"){
                    value = aValue;   
                    submissionData.setAttribute("value", value);
                    LOG.info("DADEBUG 5: " + aValue + "|" + submissionData.getAttribute(value));                    
                }
            }
            prevSubmissionData = submissionData;
            prevDataId = dataId;
            count++;            
        }
        LOG.info("created " + count + " SubmissionData");
        res.close();

        Integer intermineObjectId = getChadoDBConverter().store(prevSubmissionData);
        AppliedData aData = new AppliedData();
        aData.intermineObjectId = intermineObjectId;
        aData.itemIdentifier = prevSubmissionData.getIdentifier();
        appliedDataMap.put(prevDataId, aData);
        LOG.debug("ADMAP put: data_id " + prevDataId + "|" + intermineObjectId + " imobjid");
    }

    /**
     * Return the rows needed for data from the applied_protocol_data table.
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getAppliedDataResultSetAll(Connection connection)
    throws SQLException {
        String query =
            "select d.data_id, d.heading, d.name, d.value "
            + " , a.attribute_id, a.heading as aHeading, a.name as aName, a.value as aValue "
            + " from data d LEFT JOIN data_attribute da " 
            + " ON (d.data_id = da.data_id) "
            + " LEFT JOIN attribute a "
            + " on (da.attribute_id = a.attribute_id) ";

        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }

    
    /**
     * =====================
     *    DATA ATTRIBUTES
     * =====================
     *
     * @param connection
     * @throws SQLException
     * @throws ObjectStoreException
     */
    private void processAppliedDataAttributes(Connection connection)
    throws SQLException, ObjectStoreException {
        ResultSet res = getAppliedDataAttributesResultSet(connection);
        int count = 0;
        while (res.next()) {
            Integer dataId = new Integer(res.getInt("data_id"));
            String name  = res.getString("heading");
            String value = res.getString("value");
            String type  = res.getString("name");
            Item dataAttribute = getChadoDBConverter().createItem("SubmissionDataAttribute");

            if (name != null && !name.equals("")) {
                dataAttribute.setAttribute("name", name);
            }
            if (!StringUtils.isEmpty(value)) {
                dataAttribute.setAttribute("value", value);
            }
            if (!StringUtils.isEmpty(type)) {
                dataAttribute.setAttribute("type", type);
            }

            // setting references to dataSubmission
            dataAttribute.setReference("submissionData", appliedDataMap.get(dataId).itemIdentifier);

            // store it and add to object and maps
            Integer intermineObjectId = getChadoDBConverter().store(dataAttribute);

            count++;
        }
        LOG.info("created " + count + " data attributes");
        res.close();
    }

    /**
     * Query to get data attributes
     * This is a protected method so that it can be overridden for testing.
     *
     *
     * @param connection the db connection
     * @return the SQL result set
     * @throws SQLException if a database problem occurs
     */
    protected ResultSet getAppliedDataAttributesResultSet(Connection connection)
    throws SQLException {
        String query =
            "select da.data_id, a.heading, a.value, a.name "
            + " from data_attribute da, attribute a"
            + " where da.attribute_id = a.attribute_id";

        LOG.info("executing: " + query);
        Statement stmt = connection.createStatement();
        ResultSet res = stmt.executeQuery(query);
        return res;
    }


    /**
     * ================
     *    REFERENCES
     * ================
     * 
     * to store references between submission and submissionData
     * (1 to many)
     */
    private void setSubmissionRefs(Connection connection)
    throws ObjectStoreException {
        Iterator<Integer> subs = submissionDataMap.keySet().iterator();
        while (subs.hasNext()) {
            Integer thisSubmissionId = subs.next();
            List<Integer> dataIds = submissionDataMap.get(thisSubmissionId);
            Iterator<Integer> dat = dataIds.iterator();
            while (dat.hasNext()) {
                Integer currentId = dat.next();
                if (appliedDataMap.get(currentId).intermineObjectId == null) {
                    continue;
                }

                Reference reference = new Reference();
                reference.setName("submission");
                reference.setRefId(submissionMap.get(thisSubmissionId).itemIdentifier);

                getChadoDBConverter().store(reference,
                        appliedDataMap.get(currentId).intermineObjectId);
            }
        }
    }


    /**
     * to store references between submission and its initial submissionData
     * (initial input of the submission)
     * (1 to many)
     */
    private void setSubmissionInputRefs(Connection connection)
    throws ObjectStoreException {
        Iterator<Integer> subs = inputsMap.keySet().iterator();
        while (subs.hasNext()) {
            Integer thisSubmissionId = subs.next();
            List<Integer> dataIds = inputsMap.get(thisSubmissionId);
            Iterator<Integer> dat = dataIds.iterator();
            ReferenceList collection = new ReferenceList();
            collection.setName("inputs");
            while (dat.hasNext()) {
                Integer currentId = dat.next();
                if (appliedDataMap.get(currentId) == null) {
                    continue;
                }
                collection.addRefId(appliedDataMap.get(currentId).itemIdentifier);
            }
            getChadoDBConverter().store(collection,
                    submissionMap.get(thisSubmissionId).interMineObjectId);
        }
    }

    /**
     * to store references between submission and its resulting submissionData
     * (final output of the submission)
     * (1 to many)
     */
    private void setSubmissionResultsRefs(Connection connection)
    throws ObjectStoreException {
        Iterator<Integer> subs = outputsMap.keySet().iterator();
        while (subs.hasNext()) {
            Integer thisSubmissionId = subs.next();
            List<Integer> dataIds = outputsMap.get(thisSubmissionId);
            Iterator<Integer> dat = dataIds.iterator();
            ReferenceList collection = new ReferenceList();
            collection.setName("outputs");
            while (dat.hasNext()) {
                Integer currentId = dat.next();
                if (appliedDataMap.get(currentId) == null) {
                    continue;
                }
                collection.addRefId(appliedDataMap.get(currentId).itemIdentifier);
            }
            getChadoDBConverter().store(collection,
                    submissionMap.get(thisSubmissionId).interMineObjectId);
        }
    }

    //exp -> prot
    private void setSubmissionProtocolsRefs(Connection connection)
    throws ObjectStoreException {
        Map<Integer, List<Integer>> submissionProtocolMap = new HashMap<Integer, List<Integer>>();

        Iterator<Integer> apId = appliedProtocolMap.keySet().iterator();
        while (apId.hasNext()) {
            Integer thisAP = apId.next();
            AppliedProtocol ap = appliedProtocolMap.get(thisAP);
            addToMap(submissionProtocolMap, ap.submissionId, ap.protocolId);
        }

        Iterator<Integer> subs = submissionProtocolMap.keySet().iterator();
        while (subs.hasNext()) {
            Integer thisSubmissionId = subs.next();
            List<Integer> protocolIds = submissionProtocolMap.get(thisSubmissionId);
            Iterator<Integer> dat = protocolIds.iterator();
            ReferenceList collection = new ReferenceList();
            collection.setName("protocols");
            while (dat.hasNext()) {
                Integer currentId = dat.next();
                collection.addRefId(protocolIdRefMap.get(currentId));
            }
            getChadoDBConverter().store(collection,
                    submissionMap.get(thisSubmissionId).interMineObjectId);
        }
    }

    
    
    
    //exp -> ef
    private void setSubmissionEFactorsRefs(Connection connection)
    throws ObjectStoreException {

        LOG.info("EF REFS");

        Iterator<Integer> subs = submissionEFactorMap.keySet().iterator();
        while (subs.hasNext()) {
            Integer thisSubmissionId = subs.next();
            List<String> eFactors = submissionEFactorMap.get(thisSubmissionId);

            LOG.info("EF REFS: " + thisSubmissionId + " (" + eFactors + ")");
            
            Iterator<String> ef = eFactors.iterator();
            ReferenceList collection = new ReferenceList();
            collection.setName("experimentalFactors");
            while (ef.hasNext()) {
                String currentEF = ef.next();
                collection.addRefId(eFactorIdRefMap.get(currentEF));
                LOG.info("EF REFS: ->" + currentEF + " ref: " + eFactorIdRefMap.get(currentEF));
            }
            if (!collection.equals(null)) {
                LOG.info("EF REFS: ->" + thisSubmissionId + "|" + submissionMap.get(thisSubmissionId).interMineObjectId);
                getChadoDBConverter().store(collection,
                        submissionMap.get(thisSubmissionId).interMineObjectId);
            }
        }
    }
    
    
    
    /**
     * to store references between applied protocols and their input data
     * reverse reference: data -> next appliedProtocols
     * and between applied protocols and their output data
     * reverse reference: data -> previous appliedProtocols
     * (many to many)
     */
    private void setDAGRefs(Connection connection)
    throws ObjectStoreException {

        Iterator<Integer> apId = appliedProtocolMap.keySet().iterator();
        while (apId.hasNext()) {
            Integer thisAP = apId.next();
            AppliedProtocol ap = appliedProtocolMap.get(thisAP);
            List<Integer> dataIds = ap.inputs;
            if (!dataIds.isEmpty()) {
                Iterator<Integer> i = dataIds.iterator();
                ReferenceList collection = new ReferenceList();
                collection.setName("inputs");
                while (i.hasNext()) {
                    Integer n = i.next();
                    collection.addRefId(appliedDataMap.get(n).itemIdentifier);
                }
                getChadoDBConverter().store(collection, appliedProtocolIdMap.get(thisAP));
            }

            List<Integer> outIds = ap.outputs;
            if (!outIds.isEmpty()) {
                Iterator<Integer> i = outIds.iterator();
                ReferenceList collection = new ReferenceList();
                collection.setName("outputs");
                while (i.hasNext()) {
                    Integer n = i.next();
                    collection.addRefId(appliedDataMap.get(n).itemIdentifier);
                }
                getChadoDBConverter().store(collection, appliedProtocolIdMap.get(thisAP));
            }
        }
    }



    /**
     * to store references between submission and its resulting submissionData
     * (final output of the submission)
     * (1 to many)
     */
    private void setInOutRefs(Connection connection)
    throws ObjectStoreException {
        Iterator<Integer> subs = inOutDataMap.keySet().iterator();
        while (subs.hasNext()) {
            Integer thisId = subs.next();
            List<Integer> dataIds = inOutDataMap.get(thisId);
            Iterator<Integer> dat = dataIds.iterator();
            ReferenceList collection = new ReferenceList();
            collection.setName("resultData");
            while (dat.hasNext()) {
                Integer currentId = dat.next();
                if (appliedDataMap.get(currentId) == null) {
                    continue;
                }
                collection.addRefId(appliedDataMap.get(currentId).itemIdentifier);
            }
            getChadoDBConverter().store(collection,
                    appliedDataMap.get(thisId).intermineObjectId);
        }
    }

    /**
     * to store references between submission and its resulting submissionData
     * (final output of the submission)
     * (1 to many)
     */
    private void setOutInRefs(Connection connection)
    throws ObjectStoreException {
        Iterator<Integer> subs = outInDataMap.keySet().iterator();
        while (subs.hasNext()) {
            Integer thisId = subs.next();
            List<Integer> dataIds = outInDataMap.get(thisId);
            Iterator<Integer> dat = dataIds.iterator();
            ReferenceList collection = new ReferenceList();
            collection.setName("initialData");
            while (dat.hasNext()) {
                Integer currentId = dat.next();
                if (appliedDataMap.get(currentId) == null) {
                    continue;
                }
                collection.addRefId(appliedDataMap.get(currentId).itemIdentifier);
            }
            getChadoDBConverter().store(collection,
                    appliedDataMap.get(thisId).intermineObjectId);
        }
    }


    /**
     * maps from chado field names to ours.
     * 
     * TODO: check if up to date
     * 
     * if a field is not needed it is marked with NOT_TO_BE_LOADED
     * a check is performed and fields unaccounted for are logged.
     *
     * a specific lab field map is needed because we are using the same
     * chado table of the experiment to get the data.
     * used only for affiliation(!)
     */
    private static final Map<String, String> FIELD_NAME_MAP =
        new HashMap<String, String>();
    private static final Map<String, String> PROVIDER_FIELD_NAME_MAP =
        new HashMap<String, String>();
    private static final String NOT_TO_BE_LOADED = "this is ; illegal - anyway";

    static {
        // experiment
        // swapping back to uniquename in experiment table
        // FIELD_NAME_MAP.put("Investigation Title", "title");
        FIELD_NAME_MAP.put("Investigation Title", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Project", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Project URL", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Lab", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Experiment Description", "description");
        FIELD_NAME_MAP.put("Experimental Design", "design");
        FIELD_NAME_MAP.put("Experimental Factor Type", "factorType");
        FIELD_NAME_MAP.put("Experimental Factor Name", "factorName");
        FIELD_NAME_MAP.put("Quality Control Type", "qualityControl");
        FIELD_NAME_MAP.put("Replicate Type", "replicate");
        FIELD_NAME_MAP.put("Date of Experiment", "experimentDate");
        FIELD_NAME_MAP.put("Public Release Date", "publicReleaseDate");
        FIELD_NAME_MAP.put("dcc_id", "DCCid");
        // FIELD_NAME_MAP.put("species", "organism");
        // FIELD_NAME_MAP.put("PubMed ID", "publication");
        FIELD_NAME_MAP.put("Person Last Name", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Person Affiliation", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Person First Name", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Person Address", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Person Phone", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Person Email", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("Person Roles", NOT_TO_BE_LOADED);



        // data: parameter values
        FIELD_NAME_MAP.put("genome version", "genomeVersion");
        FIELD_NAME_MAP.put("median value", "medianValue");
        // data: result values
        //FIELD_NAME_MAP.put("transcript ID", "transcriptId");
        //FIELD_NAME_MAP.put("inner primer", "innerPrimer");
        FIELD_NAME_MAP.put("outer primer", "outerPrimer");
        FIELD_NAME_MAP.put("TraceArchive ID", "traceArchiveId");
        //FIELD_NAME_MAP.put("genbank ID", "genBankId");
        //FIELD_NAME_MAP.put("EST acc", "estAcc");
        // data: source attributes
        FIELD_NAME_MAP.put("Source Name", "source");
        FIELD_NAME_MAP.put("RNA ID", "RNAId");
        FIELD_NAME_MAP.put("Cell Type", "cellType");
        FIELD_NAME_MAP.put("Biosample #", "biosampleNr");
        // data: parameter value attributes
        FIELD_NAME_MAP.put("Unit", "unit");
        //FIELD_NAME_MAP.put("Characteristics", "characteristics");
        // data: the real thing?
        FIELD_NAME_MAP.put("Hybridization Name", "hybridizationName");
        FIELD_NAME_MAP.put("Array Data File", "arrayDataFile");
        FIELD_NAME_MAP.put("Array Design REF", "arrayDesignRef");
        FIELD_NAME_MAP.put("Derived Array Data File", "derivedArrayDataFile");
        FIELD_NAME_MAP.put("Result File", "resultFile");
        // data: obsolete?
        // FIELD_NAME_MAP.put("", "arrayMatrixDateFile");
        // FIELD_NAME_MAP.put("", "label");
        // FIELD_NAME_MAP.put("", "source");
        // FIELD_NAME_MAP.put("", "sample");
        // FIELD_NAME_MAP.put("", "extract");
        // FIELD_NAME_MAP.put("", "labelExtract");

        // protocol
        FIELD_NAME_MAP.put("Protocol Type", "type");
        FIELD_NAME_MAP.put("url protocol", "url");
        FIELD_NAME_MAP.put("species", NOT_TO_BE_LOADED);
        FIELD_NAME_MAP.put("references", NOT_TO_BE_LOADED);
    }

    static { // TODO: to remove, now lab is all static
        PROVIDER_FIELD_NAME_MAP.put("Person Affiliation", "affiliation");
        PROVIDER_FIELD_NAME_MAP.put("Person Last Name", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Experiment Description", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Investigation Title", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Experimental Design", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Experimental Factor Name", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Experimental Factor Type", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Person First Name", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Person Address", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Person Phone", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Person Email", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Person Roles", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Quality Control Type", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Replicate Type", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("PubMed ID", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Date of Experiment", NOT_TO_BE_LOADED);
        PROVIDER_FIELD_NAME_MAP.put("Public Release Date", NOT_TO_BE_LOADED);
    }

    /**
     * method to add an element to a list which is the value of a map
     * @param m       the map (<Integer, List<Integer>>)
     * @param key     the key for the map
     * @param value   the list
     */
    private void addToMap(Map<Integer, List<Integer>> m, Integer key, Integer value) {

        List<Integer> ids = new ArrayList<Integer>();

        if (m.containsKey(key)) {
            ids = m.get(key);
        }
        if (!ids.contains(value)) {
            ids.add(value);
            m.put(key, ids);
        }
    }

    /**
     * method to add an element to a list which is the value of a map
     * @param m       the map (<Integer, List<String>>)
     * @param key     the key for the map
     * @param value   the list
     */
    private void addToMap(Map<Integer, List<String>> m, Integer key, String value) {

        List<String> ids = new ArrayList<String>();

        if (m.containsKey(key)) {
            ids = m.get(key);
        }
        if (!ids.contains(value)) {
            ids.add(value);
            m.put(key, ids);
        }
    }

    
    
    
    
    /**
     * adds an element (Integer) to a list only if it is not there yet
     * @param l the list
     * @param i the element
     */
    private void addToList(List<Integer> l, Integer i) {

        if (!l.contains(i)) {
            l.add(i);
        }
    }

    /**
     * adds the elements of a list i to a list l only if they are not yet
     * there
     * @param l the receiving list
     * @param i the donating list
     */
    private void addToList(List<Integer> l, List<Integer> i) {
        Iterator <Integer> it  = i.iterator();
        while (it.hasNext()) {
            Integer thisId = it.next();
            if (!l.contains(thisId)) {
                l.add(thisId);
            }
        }
    }

    /**
     * to store identifiers in protocol maps.
     * simply store the proper values in the maps.
     * A check on the type is performed. Possibly can be avoided after more testing,
     * and the old commented lines can be reinstated (note that we need 3 methods, one
     * for each category of data.
     *
     * @param i
     * @param chadoId
     * @param intermineObjectId
     * @throws ObjectStoreException
     */
    private void storeInProtocolMaps(Item i, Integer chadoId, Integer intermineObjectId)
    throws ObjectStoreException {
        if (i.getClassName().equals("Protocol")) {
            protocolIdMap .put(chadoId, intermineObjectId);
            protocolIdRefMap .put(chadoId, i.getIdentifier());
        } else {
            throw new IllegalArgumentException("Type mismatch: expecting Protocol, getting "
                    + i.getClassName().substring(37) + " with intermineObjectId = "
                    + intermineObjectId + ", chadoId = " + chadoId);
        }
        debugMap .put(i.getIdentifier(), i.getClassName());
    }

    /**
     * to store identifiers in project maps.
     * @param i
     * @param chadoId
     * @param intermineObjectId
     * @throws ObjectStoreException
     */
    private void storeInProjectMaps(Item i, String surnamePI, Integer intermineObjectId)
    throws ObjectStoreException {
        if (i.getClassName().equals("Project")) {
            projectIdMap .put(surnamePI, intermineObjectId);
            projectIdRefMap .put(surnamePI, i.getIdentifier());
        } else {
            throw new IllegalArgumentException(
                    "Type mismatch: expecting Project, getting "
                    + i.getClassName().substring(37) + " with intermineObjectId = "
                    + intermineObjectId + ", project = " + surnamePI);
        }
        debugMap .put(i.getIdentifier(), i.getClassName());
    }

    /**
     * to store identifiers in lab maps.
     * @param i
     * @param chadoId
     * @param intermineObjectId
     * @throws ObjectStoreException
     */
    private void storeInLabMaps(Item i, String labName, Integer intermineObjectId)
    throws ObjectStoreException {
        if (i.getClassName().equals("Lab")) {
            labIdMap .put(labName, intermineObjectId);
            labIdRefMap .put(labName, i.getIdentifier());
        } else {
            throw new IllegalArgumentException(
                    "Type mismatch: expecting Lab, getting "
                    + i.getClassName().substring(37) + " with intermineObjectId = "
                    + intermineObjectId + ", lab = " + labName);
        }
        debugMap .put(i.getIdentifier(), i.getClassName());
    }


    // utilities for debugging
    // to be removed
    private void printListMap (Map<Integer, List<Integer>> m) {
        Iterator i  = m.keySet().iterator();
        while (i.hasNext()) {
            Integer current = (Integer) i.next();

            List ids = m.get(current);
            Iterator i2 = ids.iterator();
            while (i2.hasNext()) {
                LOG.debug("MAP: " + current + "|" + i2.next());
            }
        }
    }

    private void printMap (Map<Integer, Integer> m) {
        Iterator<Integer> i = m.keySet().iterator();
        while (i.hasNext()) {
            Integer thisId = i.next();
            LOG.debug("MAP: " + thisId + "|" + m.get(thisId));
        }
    }

    private void printMapAP (Map<Integer, AppliedProtocol> m) {
        Iterator<Integer> i = m.keySet().iterator();
        while (i.hasNext()) {
            Integer a = i.next();
            //LOG.info("DB APMAP ***" + a +  ": " + i2.next() );
            AppliedProtocol ap = m.get(a);
            List<Integer> ids = ap.outputs;
            Iterator<Integer> i2 = ids.iterator();
            while (i2.hasNext()) {
                LOG.debug("DB APMAP " + a +  ": " + i2.next());
            }
        }
    }
}
