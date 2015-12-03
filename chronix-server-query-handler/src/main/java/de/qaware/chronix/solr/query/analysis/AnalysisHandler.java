/*
 * Copyright (C) 2015 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package de.qaware.chronix.solr.query.analysis;

import de.qaware.chronix.solr.query.ChronixQueryParams;
import de.qaware.chronix.solr.query.analysis.collectors.AnalysisDocumentBuilder;
import de.qaware.chronix.solr.query.analysis.collectors.AnalysisQueryEvaluator;
import de.qaware.chronix.solr.query.analysis.collectors.AnalysisType;
import org.apache.lucene.document.Document;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.SearchHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/**
 * Aggregation search handler
 *
 * @author f.lautenschlager
 */
public class AnalysisHandler extends SearchHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisHandler.class);

    private final DocListProvider docListProvider;

    /**
     * <
     * Constructs an isAggregation handler
     *
     * @param docListProvider - the search provider for the DocList Result
     */
    public AnalysisHandler(DocListProvider docListProvider) {
        this.docListProvider = docListProvider;
    }

    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
        LOGGER.error("Handling analysis request {}", req);
        //First check if the request should return documents => rows > 0
        SolrParams params = req.getParams();
        String rowsParam = params.get(CommonParams.ROWS, null);
        int rows = -1;
        if (rowsParam != null) {
            rows = Integer.parseInt(rowsParam);
        }

        SolrDocumentList results = new SolrDocumentList();
        String[] filterQueries = req.getParams().getParams(CommonParams.FQ);


        //Do a query and collect them on the join function
        Map<String, List<Document>> collectedDocs = findDocuments(req, JoinFunctionEvaluator.joinFunction(filterQueries));

        //If now rows should returned, we only return the num found
        if (rows == 0) {
            results.setNumFound(collectedDocs.keySet().size());
        } else {
            //Otherwise return the aggregated time series
            long queryStart = getValue(params.get(ChronixQueryParams.QUERY_START_LONG), 0);
            long queryEnd = getValue(params.get(ChronixQueryParams.QUERY_END_LONG), Long.MAX_VALUE);

            //We have an analysis query
            List<SolrDocument> aggregatedDocs = analyze(collectedDocs,
                    AnalysisQueryEvaluator.buildAnalysis(filterQueries),
                    queryStart, queryEnd);

            results.addAll(aggregatedDocs);
            results.setNumFound(aggregatedDocs.size());
        }
        rsp.add("response", results);
        rsp.add("hits", collectedDocs.size());
        LOGGER.error("Sending response {}", rsp.getToLogAsString(String.join("-", filterQueries)) + "/");

    }

    private long getValue(String paramsValue, long defaultValue) {
        if (paramsValue == null) {
            return defaultValue;
        }
        long value = Long.parseLong(paramsValue);
        if (value == -1) {
            return defaultValue;
        }
        return value;
    }

    private Map<String, List<Document>> findDocuments(SolrQueryRequest req, Function<Document, String> collectionKey) throws IOException {
        //query all documents
        DocList result = docListProvider.doSimpleQuery(req.getParams().get(CommonParams.Q), req, 0, Integer.MAX_VALUE);
        Map<String, List<Document>> collectedDocs = new HashMap<>();

        SolrIndexSearcher searcher = req.getSearcher();
        DocIterator docIterator = result.iterator();

        while (docIterator.hasNext()) {
            AnalysisDocumentBuilder.collect(collectedDocs, searcher.doc(docIterator.nextDoc()), collectionKey);
        }
        return collectedDocs;
    }


    private List<SolrDocument> analyze(Map<String, List<Document>> collectedDocs, Map.Entry<AnalysisType, String[]> analysis, long queryStart, long queryEnd) {
        List<SolrDocument> solrDocuments = Collections.synchronizedList(new ArrayList<>(collectedDocs.size()));
        collectedDocs.entrySet().parallelStream().forEach(docs -> {
            SolrDocument doc = AnalysisDocumentBuilder.analyze(analysis, queryStart, queryEnd, docs);
            if (doc != null) {
                solrDocuments.add(doc);
            }
        });
        return solrDocuments;
    }


    @Override
    public String getDescription() {
        return "Chronix Aggregation Request Handler";
    }

    @Override
    public void init(PluginInfo info) {
        //Currently not used
    }

    @Override
    public void inform(SolrCore core) {
        //Currently not used
    }
}
