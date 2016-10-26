/*
 * Copyright (C) 2016 QAware GmbH
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
package de.qaware.chronix.solr.compaction;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;

/**
 * Loads documents lazily from solr when {@link Iterator#next()} is called. Documents are loaded in pages.
 * Main use case of this class is to iterate over large result sets.
 *
 * @author alex.christ
 */
public class LazyDocumentLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(LazyDocumentLoader.class);

    /**
     * @param searcher the index searcher
     * @param query    the query
     * @param sort     the sort criterion
     * @return found documents
     */
    public Iterable<Document> load(IndexSearcher searcher, Query query, Sort sort) {
        return new LazySolrDocumentSet(searcher, query, sort);
    }

    private class LazySolrDocumentSet implements Iterator<Document>, Iterable<Document> {
        private final IndexSearcher searcher;
        private static final int PAGE_LIMIT = 1;
        private Query query;
        private Sort sort;
        private ScoreDoc[] page;
        private int pageHitsRead;

        private LazySolrDocumentSet(IndexSearcher searcher, Query query, Sort sort) {
            this.searcher = searcher;
            this.query = query;
            this.sort = sort;
            try {
                TopDocs topDocs = searcher.searchAfter(null, query, PAGE_LIMIT, sort);
                page = topDocs.scoreDocs;
                pageHitsRead = 0;
            } catch (IOException e) {
                LOGGER.error("Could not retrieve documents", e);
            }
        }

        @Override
        public boolean hasNext() {
            return page.length > 0;
        }

        @Override
        public Document next() {
            Document result;
            try {
                result = searcher.doc(page[pageHitsRead].doc);
                pageHitsRead++;
                if (pageHitsRead == page.length) {
                    page = searcher.searchAfter(page[page.length - 1], query, PAGE_LIMIT, sort).scoreDocs;
                    pageHitsRead = 0;
                }
            } catch (IOException e) {
                LOGGER.error("Could not retrieve next lucene document", e);
                return null;
            }
            return result;
        }

        @Override
        public Iterator<Document> iterator() {
            return this;
        }
    }
}
