/*******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2012 by Pentaho : http://www.pentaho.com
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections.map.AbstractLinkedMap;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;

public class DimensionLookupCacheMap extends AbstractLinkedMap {
  private RowMetaInterface keyMeta;

  /**
   * Constructs an empty <tt>ByteArrayHashMap</tt> with the specified initial
   * capacity and load factor.
   *
   * @param  initialCapacity the initial capacity
   * @param  loadFactor      the load factor
   * @throws IllegalArgumentException if the initial capacity is negative
   *         or the load factor is nonpositive
   */
  public DimensionLookupCacheMap(int initialCapacity, float loadFactor, RowMetaInterface keyMeta) {
    super(initialCapacity, loadFactor);
    this.keyMeta = keyMeta;
  }
  
  protected boolean isEqualKey(Object key1, Object key2) {
    return equalsByteArray((byte[]) key1, (byte[]) key2);
  }
  
  public final boolean equalsByteArray(byte value[], byte cmpValue[]) {
    if(value.length == cmpValue.length) {
      for(int i = 0; i<value.length; i++) {
        if(value[i] != cmpValue[i]) {
          return false;
        }
      }
      return true;
    }
    return false;
  }
  
  /**  
   * Constructs an empty <tt>ByteArrayHashMap</tt> with the specified initial
   * capacity and the default load factor (0.75).
   *
   * @param  initialCapacity the initial capacity.
   * @throws IllegalArgumentException if the initial capacity is negative.
   */
  public DimensionLookupCacheMap(int initialCapacity, RowMetaInterface keyMeta) {
    this(initialCapacity, DEFAULT_LOAD_FACTOR, keyMeta);
  }
  
  /**
   * Constructs an empty <tt>HashMap</tt> with the default initial capacity
   * (16) and the default load factor (0.75).
   */
  public DimensionLookupCacheMap(RowMetaInterface keyMeta) {
    this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, keyMeta);
  }
  
  /**
   * Returns the entry to which the specified key is &quot;mapped&quot;,
   * or, in other words, if this index contains an entry that is equal 
   * to the given key, or {@code null} if this is not the case.
   * 
   * <p>More formally, if this index contains an entry {@code e} 
   * such that {@code key.equals(e))}, then this method returns 
   * {@code e}; otherwise it returns {@code null}.  
   * (There can be at most one such entry.)
   * 
   * @param key
   *  The key to look up.
   * @throws KettleValueException in case of a value conversion error
   *
   * @see #put(Object)
   * @see #insert(Object)
   */
  @SuppressWarnings("unchecked")
  public List<Object[]> get(byte[] key) {
    return (List<Object[]>)super.get(key);
  }  

  public void put(byte[] key, List<Object[]> value) {
	  super.put(key, value);    
  }

  @Override
  protected int hash(Object key) {
    byte[] rowKey = (byte[])key;
    try {
      return keyMeta.hashCode( RowMeta.getRow(keyMeta, rowKey) );
    } catch (KettleValueException ex) {
      throw new IllegalArgumentException(ex);
    }
  }
  
  @SuppressWarnings("unchecked")
  public List<byte[]> getKeys() {
    List<byte[]> rtn = new ArrayList<byte[]>(this.size());
    Set<byte[]> kSet = this.keySet();
    for (Iterator<byte[]> it = kSet.iterator(); it.hasNext(); ) {
      rtn.add(it.next());
    }
    return rtn;
  }
}
