package org.intermine.bio.web.util;

/*
 * Copyright (C) 2002-2011 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.keyvalue.MultiKey;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.intermine.api.InterMineAPI;
import org.intermine.api.mines.FriendlyMineManager;
import org.intermine.api.mines.FriendlyMineQueryRunner;
import org.intermine.api.mines.Mine;
import org.intermine.api.profile.ProfileManager;
import org.intermine.api.query.PathQueryExecutor;
import org.intermine.api.results.ExportResultsIterator;
import org.intermine.api.results.ResultElement;
import org.intermine.pathquery.Constraints;
import org.intermine.pathquery.OrderDirection;
import org.intermine.pathquery.PathQuery;
import org.intermine.util.CacheMap;
import org.intermine.util.Util;
import org.intermine.web.util.InterMineLinkGenerator;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Helper class for intermine links generated on report pages
 *
 * @author Julie Sullivan
 */
public final class FriendlyMineReportLinkGenerator extends InterMineLinkGenerator
{
    private static final Logger LOG = Logger.getLogger(FriendlyMineReportLinkGenerator.class);
    private static CacheMap<MultiKey, Map<String, JSONObject>> intermineLinkCache
        = new CacheMap<MultiKey, Map<String, JSONObject>>();
    private Map<String, JSONObject> filteredMines;

    /**
     * Constructor
     */
    public FriendlyMineReportLinkGenerator() {
        super();
        filteredMines = new HashMap<String, JSONObject>();
    }

    /**
     * Generate a list of genes and orthologues for remote mines.
     *
     * 1. Query local mine for orthologues for gene given.
     * 2. if orthologue found query remote mine for that gene
     * 3. if orthologue not found query remote mine for orthologues
     *
     * @param olm LinkManager
     * @param organismShortName organism.shortName, eg. C. elegans
     * @param primaryIdentifier identifier for gene
     * @param mineName NULL
     * @return map from mine to organism-->genes
     */
    public Collection<JSONObject> getLinks(FriendlyMineManager olm, String mineName,
            String organismShortName, String primaryIdentifier) {

        MultiKey key = new MultiKey(primaryIdentifier, organismShortName);
        if (intermineLinkCache.get(key) != null) {
            return intermineLinkCache.get(key).values();
        }

        Map<String, JSONObject> minesWithGene = new HashMap<String, JSONObject>();
        try {
            getMinesWithThisGene(olm, minesWithGene, organismShortName, primaryIdentifier);
            getMinesWithOrthologues(olm, filteredMines, minesWithGene, organismShortName,
                    primaryIdentifier);
            intermineLinkCache.put(key, filteredMines);
        } catch (UnsupportedEncodingException e) {
            LOG.error("error encoding organism name", e);
        } catch (JSONException e) {
            LOG.error("error generating JSON objects", e);
        }
        return filteredMines.values();
    }

    /**
     * Generate a list of genes and orthologues for remote mines.
     *
     * 1. Query local mine for orthologues for gene given.
     * 2. if orthologue found query remote mine for that gene
     * 3. if orthologue not found query remote mine for orthologues
     *
     * @param olm LinkManager
     * @param mines list of mines
     * @param organismShortName organism.shortName, eg. C. elegans
     * @param primaryIdentifier identifier for gene
     * @throws JSONException
     * @throws UnsupportedEncodingException
     */
    private static void getMinesWithThisGene(FriendlyMineManager olm,
            Map<String, JSONObject> minesToGenes, String organismShortName,
            String primaryIdentifier)
        throws JSONException, UnsupportedEncodingException {
        if (organismShortName == null) {
            LOG.error("error created links to other mines for this gene, no organism provided");
            return;
        }
        if (primaryIdentifier == null) {
            LOG.error("error created links to other mines for this gene, no primary identifier "
                    + "provided");
            return;
        }
        String encodedOrganism = URLEncoder.encode("" + organismShortName, "UTF-8");

        // query each friendly mine, return matches.  value can be identifier or symbol
        // we only return one value even if there are multiple matches, the portal at the remote
        // mine will handle duplicates
        Map<Mine, String[]> minesWithGene = getObjectInOtherMines(olm, encodedOrganism,
                primaryIdentifier);

        if (minesWithGene == null) {
            return;
        }

        for (Map.Entry<Mine, String[]> entry : minesWithGene.entrySet()) {
            Mine mine = entry.getKey();
            String[] identifiers = entry.getValue();
            minesToGenes.put(mine.getName(), getJSONOrganism(organismShortName, identifiers));
        }
    }

    /**
     * Returns list of friendly mines that contain value of interest.  Used for
     * the links on the report page.
     *
     * @param constraintValue optional additional constraint, eg. organism
     * @param identifier identifier to query
     * @param collection list of friendly mines
     * @return the list of valid mines for the given list
     */
    private static Map<Mine, String[]> getObjectInOtherMines(FriendlyMineManager olm,
            String constraintValue, String identifier) {
        Map<Mine, String[]> minesWithData = new HashMap<Mine, String[]>();
        for (Mine mine : olm.getFriendlyMines()) {
            String[] identifiers = FriendlyMineQueryRunner.getObjectInOtherMine(mine,
                    constraintValue, identifier);
            if (identifiers != null && identifiers.length == 2 && identifiers[0] != null) {
                minesWithData.put(mine, identifiers);
            }
        }
        return minesWithData;
    }

    /**
     * generate a list of genes and orthologues for remote mines.
     *
     * 1. Query local mine for orthologues for gene given.
     * 2. if orthologue found query remote mine for that gene
     * 3. if orthologue not found query remote mine for orthologues
     *
     * @param olm LinkManager
     * @param filteredMines list of mines with genes
     * @param organismShortName organism.shortName, eg. C. elegans
     * @param primaryIdentifier identifier for gene
     * @throws JSONException if we have JSON problems
     * @throws UnsupportedEncodingException
     */
    private static void getMinesWithOrthologues(FriendlyMineManager olm,
            Map<String, JSONObject> filteredMines, Map<String, JSONObject> mines,
            String organismShortName, String primaryIdentifier)
        throws JSONException, UnsupportedEncodingException {

        String encodedOrganism = URLEncoder.encode("" + organismShortName, "UTF-8");
        Map<String, Set<String>> localHomologues = getLocalOrthologues(olm, organismShortName,
                primaryIdentifier);

        for (Mine mine : olm.getFriendlyMines()) {
            final JSONObject jsonMine = getJSONMine(filteredMines, mine.getName());
            Set<JSONObject> organisms = new HashSet<JSONObject>();
            addCurrentOrganism(organisms, mines, mine.getName(), organismShortName);
            String remoteMineDefaultOrganism = mine.getDefaultValue();
            boolean queryRemoteMine = true;
            Set<String> matchingHomologues = localHomologues.get(remoteMineDefaultOrganism);

            // for default organism, do we have local orthologues?
            if (matchingHomologues != null && !matchingHomologues.isEmpty()) {
                Map<String, Set<String[]>> orthologueMap = new HashMap<String, Set<String[]>>();
                for (String homologue : matchingHomologues) {
                    // if so, does remote mine have this gene?
                    String[] homologueIdentifiers = FriendlyMineQueryRunner.getObjectInOtherMine(
                            mine, URLEncoder.encode("" + remoteMineDefaultOrganism, "UTF-8"),
                            homologue);
                    if (homologueIdentifiers != null && homologueIdentifiers.length == 2
                            && homologueIdentifiers[0] != null) {
                        Util.addToSetMap(orthologueMap, remoteMineDefaultOrganism,
                                homologueIdentifiers);
                    }
                }
                if (!orthologueMap.isEmpty()) {
                    queryRemoteMine = false;
                    JSONObject organism = getJSONOrganism(orthologueMap);
                    organisms.add(organism);
                }
            }

            /**
             * Query the remote mine if:
             * - local mine has no orthologues
             * - remote mine does not have corresponding gene for the orthologue found in local mine
             */
            if (queryRemoteMine) {
                Map<String, Set<String[]>> remoteOrthologues
                    = FriendlyMineQueryRunner.runRelatedDataQuery(mine, encodedOrganism,
                            primaryIdentifier);
                if (remoteOrthologues != null && !remoteOrthologues.isEmpty()) {
                    JSONObject organism = getJSONOrganism(remoteOrthologues);
                    organisms.add(organism);
                }
            }
            if (!organisms.isEmpty()) {
                jsonMine.put("organisms", organisms);
            }
        }
    }

    private static void addCurrentOrganism(Set<JSONObject> organisms, Map<String, JSONObject> mines,
            String mineName, String organismShortName)
        throws JSONException {
        JSONObject genes = mines.get(mineName);
        if (genes != null) {
            JSONObject organism = new JSONObject();
            organism.put("shortName", organismShortName);
            organism.put("genes", genes);
            organisms.add(organism);
        }
    }

    private static JSONObject getJSONGene(String[] identifiers)
        throws JSONException {
        JSONObject gene = new JSONObject();
        gene.put("primaryIdentifier", identifiers[0]);
        gene.put("displayIdentifier", identifiers[1]);
        return gene;
    }

    // used for genes, not homologues
    private static JSONObject getJSONOrganism(String organismName, String[] identifiers)
        throws JSONException {
        JSONObject gene = getJSONGene(identifiers);
        JSONObject organism = new JSONObject();
        organism.put("shortName", organismName);
        organism.put("orthologues", gene);
        return organism;
    }

    private static JSONObject getJSONOrganism(Map<String, Set<String[]>> orthologueMap)
        throws JSONException {
        String organismName = null;
        Set<JSONObject> genes = new HashSet<JSONObject>();
        for (Map.Entry<String, Set<String[]>> entry : orthologueMap.entrySet()) {
            organismName = entry.getKey();
            Set<String[]> identifierSets = entry.getValue();
//            Collection<String[]> parsedIdentifierSets = removeDuplicateEntries(identifierSets);
            for (String[] identifiers : identifierSets) {
                JSONObject gene = getJSONGene(identifiers);
                genes.add(gene);
            }
        }
        JSONObject organism = new JSONObject();
        organism.put("shortName", organismName);
        organism.put("orthologues", genes);
        return organism;
    }

    // query local mine for orthologues
    private static Map<String, Set<String>> getLocalOrthologues(FriendlyMineManager olm,
            String constraintValue, String identifier) {
        Map<String, Set<String>> relatedDataMap = new HashMap<String, Set<String>>();
        InterMineAPI im = olm.getInterMineAPI();
        ProfileManager profileManager = im.getProfileManager();
        PathQueryExecutor executor = im.getPathQueryExecutor(profileManager.getSuperuserProfile());
        ExportResultsIterator it = executor.execute(getLocalOrthologueQuery(im, identifier));
        while (it.hasNext()) {
            List<ResultElement> row = it.next();
            String orthologuePrimaryIdentifier = (String) row.get(0).getField();
//            String orthologueSymbol = (String) row.get(1).getField();
            String organismName = (String) row.get(2).getField();
            String orthologueIdentifer = null;
            if (!StringUtils.isEmpty(orthologuePrimaryIdentifier)) {
                orthologueIdentifer = orthologuePrimaryIdentifier;
            }
//            if (!StringUtils.isEmpty(orthologueSymbol)) {
//                orthologueIdentifer = orthologueSymbol;
//            }
            if (!StringUtils.isEmpty(orthologueIdentifer)) {
                Util.addToSetMap(relatedDataMap, organismName, orthologueIdentifer);
            }
        }
        return relatedDataMap;
    }

    private static PathQuery getLocalOrthologueQuery(InterMineAPI im, String identifier) {
        PathQuery q = new PathQuery(im.getModel());
        q.addViews("Gene.homologues.homologue.primaryIdentifier",
                "Gene.homologues.homologue.symbol",
                "Gene.homologues.homologue.organism.shortName");
        q.addOrderBy("Gene.homologues.homologue.organism.shortName", OrderDirection.ASC);
        q.addConstraint(Constraints.eq("Gene.primaryIdentifier", identifier));
        q.addConstraint(Constraints.neq("Gene.homologues.type", "paralogue"));
        return q;
    }

    private static JSONObject getJSONMine(Map<String, JSONObject> mines, String mineName)
        throws JSONException {
        JSONObject jsonMine = mines.get(mineName);
        if (jsonMine == null) {
            jsonMine = new JSONObject();
            jsonMine.put("mineName", mineName);
            mines.put(mineName, jsonMine);
        }
        return jsonMine;
    }
}
