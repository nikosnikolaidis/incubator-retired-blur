package org.apache.blur.console.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.blur.console.model.ResultRow;
import org.apache.blur.thirdparty.thrift_0_9_0.TException;
import org.apache.blur.thrift.BlurClient;
import org.apache.blur.thrift.generated.Blur.Iface;
import org.apache.blur.thrift.generated.BlurException;
import org.apache.blur.thrift.generated.BlurQuery;
import org.apache.blur.thrift.generated.BlurResult;
import org.apache.blur.thrift.generated.BlurResults;
import org.apache.blur.thrift.generated.Column;
import org.apache.blur.thrift.generated.FetchRecordResult;
import org.apache.blur.thrift.generated.FetchResult;
import org.apache.blur.thrift.generated.FetchRowResult;
import org.apache.blur.thrift.generated.Query;
import org.apache.blur.thrift.generated.Record;
import org.apache.blur.thrift.generated.Row;
import org.apache.blur.thrift.generated.ScoreType;
import org.apache.blur.thrift.generated.Selector;
import org.apache.blur.thrift.generated.User;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class SearchUtil {
	private static final String TOTAL_KEY = "total";
	private static final String DATA_KEY = "results";
	private static final String FAMILY_KEY = "families";
	
	private static final String ROW_ROW_OPTION = "rowrow";
	private static final String RECORD_RECORD_OPTION = "recordrecord";
	
	public static Map<String, Object> search(Map<String, String[]> params, String remoteHost) throws IOException, BlurException, TException {
		String table = params.get("table")[0];
		String query = params.get("query")[0];
		String rowQuery = params.get("rowRecordOption")[0];
		String start = params.get("start")[0];
		String fetch = params.get("fetch")[0];
		String[] families = params.get("families[]");
		
		if (families == null || families.length == 0) {
			return fullTextSearch(table, query, remoteHost);
		}
		
		if (ArrayUtils.contains(families, "rowid")) {
			return fetchRow(table, query, families, remoteHost);
		}
		
		if (ArrayUtils.contains(families, "recordid")) {
			return fetchRecord(table, query, families);
		}
		
		return searchAndFetch(table, query, rowQuery, start, fetch, families, remoteHost);
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Map<String, Object> searchAndFetch(String table, String query, String rowQuery, String start, String fetch, String[] families, String remoteHost) throws IOException, BlurException, TException {
		Iface client = BlurClient.getClient(Config.getConnectionString());
		setUser(client, remoteHost);
		
		boolean recordsOnly = RECORD_RECORD_OPTION.equalsIgnoreCase(rowQuery);
		
		BlurQuery blurQuery = new BlurQuery();
		
		Query q = new Query(query, ROW_ROW_OPTION.equalsIgnoreCase(rowQuery), ScoreType.SUPER, null, null);
		blurQuery.setQuery(q);
		blurQuery.setStart(Long.parseLong(start));
		blurQuery.setFetch(Integer.parseInt(fetch));
		
		Selector s = new Selector();
		s.setRecordOnly(recordsOnly);
		s.setColumnFamiliesToFetch(new HashSet<String>(Arrays.asList(families)));
		blurQuery.setSelector(s);
		
		BlurResults blurResults = client.query(table, blurQuery);
		
		Map<String, Object> results = new HashMap<String, Object>();
		results.put(TOTAL_KEY, blurResults.getTotalResults());
		
		Map<String, List> rows = new HashMap<String, List>();
		for (BlurResult result : blurResults.getResults()) {
			FetchResult fetchResult = result.getFetchResult();
			
			if (recordsOnly) {
				// Record Result
				FetchRecordResult recordResult = fetchResult.getRecordResult();
				Record record = recordResult.getRecord();
				
				String family = record.getFamily();
				
				List<Map<String, String>> fam = (List<Map<String, String>>) getFam(family, rows, recordsOnly);
				fam.add(buildRow(record.getColumns(), record.getRecordId()));
			} else {
				// Row Result
				FetchRowResult rowResult = fetchResult.getRowResult();
				Row row = rowResult.getRow();
				if (row.getRecords() == null || row.getRecords().size() == 0) {
					for(String family : families) {
						List<ResultRow> fam = (List<ResultRow>) getFam(family, rows, recordsOnly);
						getRow(row.getId(), fam);
					}
				} else {
					for (Record record : row.getRecords()) {
						String family = record.getFamily();
						
						List<ResultRow> fam = (List<ResultRow>) getFam(family, rows, recordsOnly);
						ResultRow rowData = getRow(row.getId(), fam);
						rowData.getRecords().add(buildRow(record.getColumns(), record.getRecordId()));
					}
				}
			}
		}
		
		results.put(FAMILY_KEY, new HashSet<String>(Arrays.asList(families)));
		results.put(DATA_KEY, rows);
		
		return results;
	}
	
	private static Map<String, Object> fullTextSearch(String table, String query, String remoteHost) throws IOException, BlurException, TException {
		Iface client = BlurClient.getClient(Config.getConnectionString());
		setUser(client, remoteHost);
		
		BlurQuery blurQuery = new BlurQuery();
		
		Query q = new Query(query, true, ScoreType.SUPER, null, null);
		blurQuery.setQuery(q);
		BlurResults blurResults = client.query(table, blurQuery);
		
		Map<String, Object> results = new HashMap<String, Object>();
		results.put(TOTAL_KEY, blurResults.getTotalResults());
		return results;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Map<String, Object> fetchRow(String table, String query, String[] families, String remoteHost) throws IOException, BlurException, TException {
		Iface client = BlurClient.getClient(Config.getConnectionString());
		setUser(client, remoteHost);
		
		Selector selector = new Selector();
		String rowid = StringUtils.remove(query, "rowid:");
		selector.setRowId(rowid);
		selector.setColumnFamiliesToFetch(new HashSet<String>(Arrays.asList(families)));
		
		FetchResult fetchRow = client.fetchRow(table, selector);
		
		Map<String, Object> results = new HashMap<String, Object>();
		results.put(TOTAL_KEY, fetchRow.getRowResult().getRow() == null ? 0 : 1);
		
		Map<String, List> rows = new HashMap<String, List>();
		Row row = fetchRow.getRowResult().getRow();
		if (row != null) {
			for (Record record : row.getRecords()) {
				String family = record.getFamily();
				
				List<ResultRow> fam = (List<ResultRow>) getFam(family, rows, false);
				ResultRow rowData = getRow(row.getId(), fam);
				rowData.getRecords().add(buildRow(record.getColumns(), record.getRecordId()));
			}
		}
		results.put(DATA_KEY, rows);
		results.put(FAMILY_KEY, new HashSet<String>(Arrays.asList(families)));
		
		return null;
	}
	
	private static Map<String, Object> fetchRecord(String table, String query, String[] families) throws IOException {
//		Iface client = BlurClient.getClient(Config.getConnectionString());
		return null;
	}
	
	private static Map<String, String> buildRow(List<Column> columns, String recordid) {
		Map<String, String> map = new TreeMap<String, String>();
		map.put("recordid", recordid);
		
		for (Column column : columns) {
			map.put(column.getName(), column.getValue());
		}
		
		return map;
	}
	
	@SuppressWarnings("rawtypes")
	private static List getFam(String fam, Map<String, List> results, boolean recordOnly) {
		List famResults = results.get(fam);
		
		if (famResults == null) {
			if (recordOnly) {
				famResults = new ArrayList<Map<String, String>>();				
			} else {
				famResults = new ArrayList<ResultRow>();
			}
			results.put(fam, famResults);
		}
		
		return famResults;
	}
	
	private static ResultRow getRow(String rowid, List<ResultRow> rows) {
		ResultRow row = null;
		for(ResultRow r : rows) {
			if (r.getRowid().equals(rowid)) {
				row = r;
				break;
			}
		}
		
		if (row == null) {
			row = new ResultRow(rowid);
			rows.add(row);
		}
		
		return row;
	}
	
	private static void setUser(Iface client, String username) throws TException {
		if (Config.getUserProperties() != null) {
			client.setUser(new User(username, Config.getUserProperties()));
		}
	}
}
