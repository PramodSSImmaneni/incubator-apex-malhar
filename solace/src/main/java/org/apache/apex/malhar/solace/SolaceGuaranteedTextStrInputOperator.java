/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.apex.malhar.solace;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.TextMessage;
import com.datatorrent.api.DefaultOutputPort;


//public class SolaceGuaranteedTextStrInputOperator extends AbstractSolaceGuaranteedInputOperator<String>
public class SolaceGuaranteedTextStrInputOperator extends AbstractSolaceGuaranteedIdempotentInputOperator<String>
{
  public final transient DefaultOutputPort<String> output = new DefaultOutputPort<String>();

  @Override
  protected String convert(BytesXMLMessage message)
  {
    String out = null;
    if (message instanceof TextMessage) {
      out = ((TextMessage)message).getText();
    } else if (message instanceof BytesMessage) {

      out = new String(((BytesMessage)message).getData());
    }

    return out;
  }

  @Override
  protected void emitTuple(String tuple)
  {
    output.emit(tuple);
  }


  @Override
  protected void clearConsumer() throws JCSMPException
  {
    // TODO Auto-generated method stub
  }

}
