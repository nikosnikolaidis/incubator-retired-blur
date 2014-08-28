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
package org.apache.blur.manager.command.primitive;

import java.io.IOException;

import org.apache.blur.manager.command.IndexContext;
import org.apache.blur.manager.command.IndexReadCommand;

@SuppressWarnings("serial")
public class DocumentCount extends BaseCommand implements IndexReadCommand<Integer> {

  private static final String DOC_COUNT = "docCount";

  @Override
  public String getName() {
    return DOC_COUNT;
  }

  @Override
  public Integer execute(IndexContext context) throws IOException {
    return context.getIndexReader().numDocs();
  }

}
