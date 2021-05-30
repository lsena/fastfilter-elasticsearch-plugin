/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.elasticsearch.lsena.fastfilter;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.FilterScript;
import org.elasticsearch.script.FilterScript.LeafFactory;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.ScriptFactory;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.index.fielddata.ScriptDocValues;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import java.util.Base64;
import java.nio.ByteBuffer;

import org.roaringbitmap.RoaringBitmap;

/**
 * An example script plugin that adds a {@link ScriptEngine}
 * implementing fast_filter.
 */
public class FastFilterPlugin extends Plugin implements ScriptPlugin {

	@Override
	public ScriptEngine getScriptEngine(
			Settings settings,
			Collection<ScriptContext<?>> contexts
			) {
		return new MyFastFilterEngine();
	}

	// tag::fast_filter
	private static class MyFastFilterEngine implements ScriptEngine {
		@Override
		public String getType() {
			return "fast_filter";
		}

		@Override
		public <T> T compile(
				String scriptName,
				String scriptSource,
				ScriptContext<T> context,
				Map<String, String> params
				) {
			if (context.equals(FilterScript.CONTEXT) == false) {
				throw new IllegalArgumentException(getType()
						+ " scripts cannot be used for context ["
						+ context.name + "]");
			}
			// we use the script "source" as the script identifier
			// in this case, we use the name fast_filter
			if ("fast_filter".equals(scriptSource)) {
				FilterScript.Factory factory = new FastFilterFactory();
				return context.factoryClazz.cast(factory);
			}
			throw new IllegalArgumentException("Unknown script name "
					+ scriptSource);
		}

		@Override
		public void close() {
			// optionally close resources
		}

		@Override
		public Set<ScriptContext<?>> getSupportedContexts() {
			return this.getSupportedContexts();
			//return Set.of(ScoreScript.CONTEXT);
		}

		private static class FastFilterFactory implements FilterScript.Factory,
		ScriptFactory {
			@Override
			public boolean isResultDeterministic() {
				// FastFilterLeafFactory only uses deterministic APIs, this
				// implies the results are cacheable.
				return true;
			}

			@Override
			public LeafFactory newFactory(
					Map<String, Object> params,
					SearchLookup lookup
					) {
				return new FastFilterLeafFactory(params, lookup);
			}
		}

		private static class FastFilterLeafFactory implements LeafFactory {
			private final Map<String, Object> params;
			private final SearchLookup lookup;
			private final String fieldName;
			private final String opType;
			private final String terms;

			private FastFilterLeafFactory(
					Map<String, Object> params, SearchLookup lookup) {
				if (params.containsKey("field") == false) {
					throw new IllegalArgumentException(
							"Missing parameter [field]");
				}
				if (params.containsKey("terms") == false) {
					throw new IllegalArgumentException(
							"Missing parameter [terms]");
				}
				this.params = params;
				this.lookup = lookup;
				opType = params.get("operation").toString();
				fieldName = params.get("field").toString();
				terms = params.get("terms").toString();
			}


			@Override
			public FilterScript newInstance(LeafReaderContext context) throws IOException {
				final byte[] decodedTerms = Base64.getDecoder().decode(terms);
				final ByteBuffer buffer = ByteBuffer.wrap(decodedTerms);
				RoaringBitmap rBitmap = new RoaringBitmap();
				rBitmap.deserialize(buffer);

				return new FilterScript(params, lookup, context) {

					@Override
					public boolean execute() {
						try {

							final int docId;
							if (fieldName.equals("_id")) {
								final ScriptDocValues.Strings fieldNameValue = 
										(ScriptDocValues.Strings)getDoc().get(fieldName);
								docId = Integer.parseInt(fieldNameValue.getValue());	
							} else {
								// TODO: there must be a better way to do this
								// we do not need the whole doc, just the value
								// TODO2: the selected field could be a string and this will explode
								final ScriptDocValues.Longs fieldNameValue = 
										(ScriptDocValues.Longs)getDoc().get(fieldName);
								docId = (int)fieldNameValue.getValue();	
							}

							if (opType.equals("exclude") && rBitmap.contains(docId)) {
								return false;
							}
							else if (opType.equals("include") && !rBitmap.contains(docId)) {
								return false;
							}
							return true;

						} catch (Exception exception) {
							throw exception;
						}
					}
				};
			}
		}
	}
	// end::fast_filter
}
