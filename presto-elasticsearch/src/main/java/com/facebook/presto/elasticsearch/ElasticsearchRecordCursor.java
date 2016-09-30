/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.elasticsearch;

import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.type.Type;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.SearchHit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

public class ElasticsearchRecordCursor
        implements RecordCursor
{
    private static final Logger log = Logger.get(ElasticsearchRecordCursor.class);
    private final List<ElasticsearchColumnHandle> columnHandles;
    private final Map<String, Integer> jsonPathToIndex;
    private final Iterator<SearchHit> lines;
    private long totalBytes;
    private List<Object> fields;

    public ElasticsearchRecordCursor(List<ElasticsearchColumnHandle> columnHandles, ElasticsearchTableSource tableSource, ElasticsearchClient elasticsearchClient)
    {
        this.columnHandles = columnHandles;
        this.jsonPathToIndex = new HashMap();
        this.totalBytes = 0;

        for (int i = 0; i < columnHandles.size(); i++) {
            this.jsonPathToIndex.put(columnHandles.get(i).getColumnJsonPath(), i);
        }

        this.lines = getRows(tableSource, elasticsearchClient).iterator();
    }

    @Override
    public long getTotalBytes()
    {
        return totalBytes;
    }

    @Override
    public long getCompletedBytes()
    {
        return totalBytes;
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    @Override
    public Type getType(int field)
    {
        checkArgument(field < columnHandles.size(), "Invalid field index");
        return columnHandles.get(field).getColumnType();
    }

    @Override
    public boolean advanceNextPosition()
    {
        if (!lines.hasNext()) {
            return false;
        }
        SearchHit hit = lines.next();

        fields = new ArrayList(Collections.nCopies(columnHandles.size(), null));

        fields.set(jsonPathToIndex.get("_id"), hit.getId());
        fields.set(jsonPathToIndex.get("_index"), hit.getIndex());

        Map<String, Object> map = hit.getSource();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String jsonPath = entry.getKey().toString();
            Object entryValue = entry.getValue();

            fields.set(jsonPathToIndex.get(jsonPath), entryValue);
        }

        totalBytes += fields.size();

        return true;
    }

    @Override
    public boolean getBoolean(int field)
    {
        checkFieldType(field, BOOLEAN);
        return (Boolean) getFieldValue(field);
    }

    @Override
    public long getLong(int field)
    {
        checkFieldType(field, BIGINT);
        return Long.valueOf(String.valueOf(getFieldValue(field)));
    }

    @Override
    public double getDouble(int field)
    {
        checkFieldType(field, DOUBLE);
        return (Double) getFieldValue(field);
    }

    @Override
    public Slice getSlice(int field)
    {
        checkFieldType(field, VARCHAR);
        return Slices.utf8Slice(String.valueOf(getFieldValue(field)));
    }

    @Override
    public Object getObject(int field)
    {
        return null;
    }

    @Override
    public boolean isNull(int field)
    {
        checkArgument(field < columnHandles.size(), "Invalid field index");
        return getFieldValue(field) == null;
    }

    private void checkFieldType(int field, Type expected)
    {
        Type actual = getType(field);
        checkArgument(actual.equals(expected), "Expected field %s to be type %s but is %s", field, expected, actual);
    }

    @Override
    public void close()
    {
    }

    List<SearchHit> getRows(ElasticsearchTableSource tableSource, ElasticsearchClient elasticsearchClient)
    {
        List<SearchHit> result = new ArrayList<>();
        String clusterName = tableSource.getClusterName();
        String hostAddress = tableSource.getHostAddress();
        int port = tableSource.getPort();
        String index = tableSource.getIndex();
        String type = tableSource.getType();

        log.debug(String.format("Connecting to cluster %s from %s:%d, index %s, type %s", clusterName, hostAddress, port, index, type));
        Client client = elasticsearchClient.getInternalClients().get(clusterName);
        SearchResponse scrollResp = client
                .prepareSearch(index != null && !index.isEmpty() ? index : "_all")
                .setTypes(tableSource.getType())
                .setSearchType(SearchType.SCAN)
                .setScroll(new TimeValue(10000))
                .setSize(5000)
                .setSize(50000)
                .execute()
                .actionGet();

        //Scroll until no hits are returned
        while (true) {
            for (SearchHit hit : scrollResp.getHits().getHits()) {
                result.add(hit);
            }

            scrollResp = client
                .prepareSearchScroll(scrollResp.getScrollId())
                .setScroll(new TimeValue(10000))
                .execute()
                .actionGet();

            if (scrollResp.getHits().getHits().length == 0) {
                break;
            }
        }

        return result;
    }

    Object getFieldValue(int field)
    {
        checkState(fields != null, "Cursor has not been advanced yet");
        return fields.get(field);
    }
}
