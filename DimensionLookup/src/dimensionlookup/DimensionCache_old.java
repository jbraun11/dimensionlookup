/*! ******************************************************************************
*
* Pentaho Data Integration
*
* Copyright (C) 2002-2013 by Pentaho : http://www.pentaho.com
*
*******************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
******************************************************************************/

package dimensionlookup;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.h2.tools.Server;
import org.pentaho.di.core.exception.KettleDatabaseException;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaDate;

/**
 * This class will act as a special purpose dimension Cache.
 * The idea here is to not only cache the last version of a dimension entry, but all versions.
 * So basically, the entry key is the natural key as well as the from-to date range.
 * 
 * The way to achieve that result is to keep a sorted list in memory.
 * Because we want as few conversion errors as possible, we'll use the same row as we get from the database.
 * 
 * @author john
 *
 */
public class DimensionCache_old implements Comparator<Object[]> {
	private DimensionLookupData data;
	private DimensionLookupMeta meta;
	private RowMetaInterface rowMeta;
	private HashMap rowCache;
	private int[] keyIndexes;
	private int fromDateIndex;
	private int toDateIndex;
	private boolean initialLoad = false;
	
	/**
	 * Create a new dimension cache object
	 * 
	 * @param rowMeta the description of the rows to store
	 * @param keyIndexes the indexes of the natural key (in that order)
	 * @param fromDateIndex the field index where the start of the date range can be found 
	 * @param toDateIndex the field index where the end of the date range can be found
	 */
	public DimensionCache_old(DimensionLookupMeta meta, DimensionLookupData data)
	{
		this(data.inputRowMeta, meta, data);
	}
	
	public DimensionCache_old(RowMetaInterface rowMeta, DimensionLookupMeta meta, DimensionLookupData data)
	{		
		this.data = data;
		this.meta = meta;
		this.rowMeta = rowMeta;
		this.keyIndexes = data.preloadKeyIndexes;
		this.fromDateIndex = data.preloadFromDateIndex;
		this.toDateIndex = data.preloadToDateIndex;
		this.rowCache = new DimensionLookupCacheMap(meta.getCacheSize()>0 ? meta.getCacheSize() : 5000, data.cacheKeyRowMeta);
	}

	/**
	 * Add a row to the back of the list
	 * @param row the row to add
	 * @throws IOException 
	 * @throws ClassNotFoundException 
	 */
	public void addRow(Object[] keyValues, Object[] row) throws ClassNotFoundException, IOException 
	{		
		// check if cache is full
		if (meta.getCacheSize() > 0 && rowCache.size() >= meta.getCacheSize())
		{
			byte[] firstKey = (byte[]) rowCache.firstKey();
			rowCache.remove(firstKey);
		}
		
		// create keypart and add row to cache
		byte[] keyPart = RowMeta.extractData(data.cacheKeyRowMeta, keyValues);
		
		List<Object[]> rows = new ArrayList<Object[]>();
		rows.add(row);
		
		rowCache.put(keyPart, rows);
		
		// check if this is the initial load and input is sorted
		// if so remove previously cached keys b/c we know we will not see those again (improves memory consumption for initial dim load)
		if (initialLoad && meta.isSortedInput())
		{
			List<byte[]> keys = rowCache.getKeys();
			
			for (byte[] key : keys)
			{
				if (!Arrays.equals(key,keyPart))
				{
					rowCache.remove(key);
				}
			}
		}
	}

	/**
	 * Looks up a row in the (sorted) cache.
	 * 
	 * @param lookupRowData The data of the lookup row.  Make sure that on the index of the from date, you put the lookup date.
	 * @throws IOException 
	 * @throws ClassNotFoundException 
	 * @throws a KettleException in case there are conversion errors during the lookup of the row
	 */
	public Object[] lookupRow(Object[] lookupRowData) throws KettleException, ClassNotFoundException, IOException {
		try {
			// First perform the lookup!
			//
			Object[] lookupRow = assembleCacheKey(lookupRowData);
			byte[] keyPart = RowMeta.extractData(data.cacheKeyRowMeta, lookupRow);
			List<Object[]> rows = rowCache.get(keyPart);
			if (rows != null) 
			{
				// What we have now is the set of rows that correspond to those keys
				// First need to sort the rows and then search
				// Since we only compare on the start of the date range (see also: below in Compare.compare()) 
				// we will usually get the insertion point of the row
				// However, that insertion point is the actual row index IF the supplied lookup date (in the lookup row) is between
				//
				// This row at the insertion point where the natural keys match and the start 
				// sort rows
				sortRows(rows);
				for (Object[] row : rows)
				{					
					// The natural keys match, now see if the lookup date (lookupRowData[fromDateIndex]) is between
					// row[fromDateIndex] and row[toDateIndex]
					//

					Date fromDate   = (Date) row[fromDateIndex];
					Date toDate     = (Date) row[toDateIndex];
					Date lookupDate = (Date) lookupRowData[fromDateIndex];
					
					if (fromDate==null && toDate!=null) {
						// This is the case where the fromDate is null and the toDate is not.
						// This is a special case where null as a start date means -Infinity
						//
						if (toDate.compareTo(lookupDate)>0) {
							return row; // found the key!!
						} else {
							// This should never happen, it's a flaw in the data or the binary search algorithm...
							// TODO: print the row perhaps?
							//
							throw new KettleException("Key sorting problem detected during row cache lookup: the lookup date of the row retrieved is higher than or equal to the end of the date range.");  
						}
					} else if (fromDate!=null && toDate==null) {
						// This is the case where the toDate is null and the fromDate is not.
						// This is a special case where null as an end date means +Infinity
						//
						if (fromDate.compareTo(lookupDate)<=0) {
							return row; // found the key!!
						} else {
							// This should never happen, it's a flaw in the data or the binary search algorithm...
							// TODO: print the row perhaps?
							//
							throw new KettleException("Key sorting problem detected during row cache lookup: the lookup date of the row retrieved is lower than or equal to the start of the date range.");  
						}
					} else {
						// Both dates are available: simply see if the lookup date falls in between...
						//
						if (fromDate.compareTo(lookupDate)<=0 && toDate.compareTo(lookupDate)>0) 
						{
							return row;
						} 
						else if (meta.isUpdate())
						{
							// This should never happen, it's a flaw in the data or the binary search algorithm...
							// TODO: print the row perhaps?
							//
							throw new KettleException("Key sorting problem detected during row cache lookup: the lookup date of the input row is not within range of the latest record.");  
						}
					}
				}
			}
			return null;
		}
		catch(RuntimeException e) {			
			throw new KettleException(e);
		}
	}
	
	public void sortRows(List<Object[]> rowList) {
		Collections.sort(rowList, this);
	}
	
	/**
	 * Compare 2 rows of data using the natural keys and indexes specified.
	 * 
	 * @param o1
	 * @param o2
	 * @return
	 */
	public int compare(Object[] o1, Object[] o2) {
		try {

			// See if the start of the date range of o2 falls between the start and end of o2
			//
			ValueMetaInterface dateMeta = new ValueMetaDate();
			
			Date fromDate  = (Date) o1[fromDateIndex];
			Date toDate    = (Date) o1[toDateIndex];
			Date lookupDate = (Date) o2[fromDateIndex];
			
			if (toDate!=null) {
				int cmpTo = lookupDate.compareTo(toDate);
				
				if (fromDate==null && cmpTo<0) return 0; // match!
				
				int cmpFrom = lookupDate.compareTo(fromDate);
	
				if (fromDate!=null && cmpFrom>=0 && cmpTo<0) return 0; // match
			}
			
			if (dateMeta.compare(o1[fromDateIndex], o2[fromDateIndex]) == 0) {
				return -1;
			} else return dateMeta.compare(o1[fromDateIndex], o2[fromDateIndex]);				
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * @return the rowMeta
	 */
	public RowMetaInterface getRowMeta() {
		return rowMeta;
	}

	/**
	 * @param rowMeta the rowMeta to set
	 */
	public void setRowMeta(RowMetaInterface rowMeta) {
		this.rowMeta = rowMeta;
	}

	/**
	 * @param rowCache the rowCache to set
	 * @throws IOException 
	 * @throws ClassNotFoundException 
	 * @throws KettleValueException
	 * @throws KettleDatabaseException 
	 */
	public void setRowCache(ResultSet rowSet, DimensionLookup dl) throws IOException, ClassNotFoundException, KettleValueException, KettleDatabaseException 
	{
		try
		{
			boolean stop = false;
			
			if (rowSet != null) 
			{
				int i = 0;
				int j = 1;
				long startTime = System.currentTimeMillis();
				
				while (!stop) 
				{
					Object[] row = data.db.getRow(rowSet, null, rowMeta);
					if (row != null)
					{
						List<Object[]> rows = null;
						Object[] lookupRow = assembleCacheKey(row);
						byte[] keyPart = RowMeta.extractData(data.cacheKeyRowMeta, lookupRow);
						if (rowCache.containsKey(keyPart) && !meta.isUpdate())
						{
							rows = rowCache.get(keyPart);
							rows.add(row);
						}
						else
						{
							rows = new ArrayList<Object[]>();
							rows.add(row);
						}
						
						rowCache.put(keyPart, rows);
					}
					else
					{
						stop = true;
					}
					
					i++;
					long endTime = System.currentTimeMillis();
					long totalTime = endTime - startTime;
					
					if ((float)totalTime/(float)60000 > j)
					{
						dl.logBasic("Current rows in cache: " + i);
						j++;
					}
				}
				long endTime = System.currentTimeMillis();
				long totalTime = endTime - startTime;
				dl.logBasic("Caching complete...");
				dl.logBasic("Total rows added to cached :" + i);
				dl.logBasic("Total time taken: " + (float)totalTime/(float)1000/(float)60);
				
				if (i == 1)
				{
					initialLoad = true;
				}
			}
		}
		catch (Exception e)
		{
			throw new KettleDatabaseException("Unable to get list of rows from ResultSet : ", e);
	    }
	}

	public Object[] getVersionForKey(Object[] lookupRowData, Long version, int versionFieldIndex) throws Exception 
	{
		try
		{
			Object[] lookupRow = assembleCacheKey(lookupRowData);
			byte[] keyPart = RowMeta.extractData(data.cacheKeyRowMeta, lookupRow);
			List<Object[]> rows = rowCache.get(keyPart);
			
			for (Object[] row : rows)
			{
				long rowVersion = Long.parseLong(row[versionFieldIndex].toString());
				
				if (rowVersion == version)
				{
					return row;
				}
			}
			return null;
		}
		catch (Exception ex)
		{
			throw ex;
		}
	}
	
	public Object[] assembleCacheKey(Object[] row) throws KettleValueException
	{
		Object[] lookupRow;

	    lookupRow = new Object[meta.getKeyStream().length];
	    
		// Construct the lookup row...
		for (int i = 0; i < meta.getKeyStream().length; i++) 
		{
			Object fieldValue = row[rowMeta.indexOfValue(meta.getKeyLookup()[i])];
			lookupRow[i] = (fieldValue instanceof String) ? fieldValue.toString().toLowerCase() : fieldValue;
		}
		return lookupRow;	
	}
	
	public Object[] generateCacheRow(Object[] row, RowMetaInterface currentRowMeta) throws KettleValueException
	{
		Object[] cacheRow = new Object[rowMeta.size()];
		return generateCacheRow(row, currentRowMeta, cacheRow, rowMeta);
	}
	
	public Object[] generateCacheRow(Object[] row, RowMetaInterface currentRowMeta, Object[] updateRow, RowMetaInterface updateRowMeta) throws KettleValueException
	{		
		for (int i = 0; i < updateRowMeta.size(); i++)
		{
			String cacheRowColumnName = updateRowMeta.getValueMeta(i).getName();
			
			for (int j = 0; j < currentRowMeta.size(); j++)
			{
				String currentRowColumnName = currentRowMeta.getValueMeta(j).getName();
				if (cacheRowColumnName.equalsIgnoreCase(currentRowColumnName))
				{
					updateRow[i] = row[j]; //updateRowMeta.getValueMeta(i).convertData(updateRowMeta.getValueMeta(i), row[j]);
					break;
				}
			}
		}
		
		return updateRow;
	}

	/**
	 * @return the keyIndexes
	 */
	public int[] getKeyIndexes() {
		return keyIndexes;
	}

	/**
	 * @param keyIndexes the keyIndexes to set
	 */
	public void setKeyIndexes(int[] keyIndexes) {
		this.keyIndexes = keyIndexes;
	}

	/**
	 * @return the fromDateIndex
	 */
	public int getFromDateIndex() {
		return fromDateIndex;
	}

	/**
	 * @param fromDateIndex the fromDateIndex to set
	 */
	public void setFromDateIndex(int fromDateIndex) {
		this.fromDateIndex = fromDateIndex;
	}

	/**
	 * @return the toDateIndex
	 */
	public int getToDateIndex() {
		return toDateIndex;
	}

	/**
	 * @param toDateIndex the toDateIndex to set
	 */
	public void setToDateIndex(int toDateIndex) {
		this.toDateIndex = toDateIndex;
	}
}
