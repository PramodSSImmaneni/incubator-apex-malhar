/*
 *  Copyright (c) 2012-2015 Malhar, Inc.
 *  All Rights Reserved.
 */

package com.datatorrent.lib.appdata.gpo;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.bytes.ByteIterator;
import it.unimi.dsi.fastutil.bytes.ByteList;

import java.util.Collection;
import java.util.Iterator;

public class GPOByteArrayList extends ByteArrayList
{
  private static final long serialVersionUID = 201503091653L;

  protected GPOByteArrayList(byte[] a, boolean dummy)
  {
    super(a, dummy);
  }

  public GPOByteArrayList(int capacity)
  {
    super(capacity);
  }

  public GPOByteArrayList()
  {
  }

  public GPOByteArrayList(Collection<? extends Byte> c)
  {
    super(c);
  }

  public GPOByteArrayList(ByteCollection c)
  {
    super(c);
  }

  public GPOByteArrayList(ByteList l)
  {
    super(l);
  }

  public GPOByteArrayList(byte[] a)
  {
    super(a);
  }

  public GPOByteArrayList(byte[] a, int offset, int length)
  {
    super(a, offset, length);
  }

  public GPOByteArrayList(Iterator<? extends Byte> i)
  {
    super(i);
  }

  public GPOByteArrayList(ByteIterator i)
  {
    super(i);
  }

  public boolean add(byte[] bytes)
  {
    for(int byteCounter = 0;
        byteCounter < bytes.length;
        byteCounter++) {
      this.add(bytes[byteCounter]);
    }

    return true;
  }
}
