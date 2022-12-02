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

import org.apache.lucene.index.SortedNumericDocValues;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.*;
import org.elasticsearch.script.FilterScript.LeafFactory;
import org.elasticsearch.search.lookup.SearchLookup;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * RoaringBitmap plugin that allows filtering documents
 * using a base64 encoded roaringbitmap list of integers.
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
			if (!context.equals(FilterScript.CONTEXT)) {
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
			return Set.of(ScoreScript.CONTEXT);
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
				final byte[] decodedTerms = Base64.getDecoder().decode(params.get("terms").toString());
				final ByteBuffer buffer = ByteBuffer.wrap(decodedTerms);
				RoaringBitmap rBitmap = new RoaringBitmap();
				try {
					rBitmap.deserialize(buffer);
				}
				catch (IOException e) {
					// Do something here
				}
				return new FastFilterLeafFactory(params, lookup, rBitmap);
			}
		}

		private static class FastFilterLeafFactory implements LeafFactory {
			private final Map<String, Object> params;
			private final SearchLookup lookup;
			private final String fieldName;
			private final String opType;
			private final RoaringBitmap rBitmap;
			private final boolean include;
			private final boolean exclude;

			private FastFilterLeafFactory(Map<String, Object> params, SearchLookup lookup, RoaringBitmap rBitmap) {
				if (!params.containsKey("field")) {
					throw new IllegalArgumentException(
							"Missing parameter [field]");
				}
				if (!params.containsKey("terms")) {
					throw new IllegalArgumentException(
							"Missing parameter [terms]");
				}
				this.params = params;
				this.lookup = lookup;
				this.rBitmap = rBitmap;
				opType = params.get("operation").toString();
				fieldName = params.get("field").toString();
				include = opType.equals("include");
				exclude = !include;
			}


			@Override
			public FilterScript newInstance(DocReader docReader)
					throws IOException {
				DocValuesDocReader dvReader = ((DocValuesDocReader) docReader);
				SortedNumericDocValues docValues = dvReader.getLeafReaderContext()
						.reader().getSortedNumericDocValues(fieldName);

				if (docValues == null) {
					/*
					 * the field and/or docValues doesn't exist in this segment
					 */
					return new FilterScript(params, lookup, docReader) {
						@Override
						public boolean execute() {
							// return true when used as exclude filter
							return exclude;
						}
					};
				}

				return new FilterScript(params, lookup, docReader) {
					int currentDocid = -1;
					@Override
					public void setDocument(int docid) {
						/*
						 * advance has undefined behavior calling with
						 * a docid <= its current docid
						 */
						try {
							docValues.advance(docid);
						} catch (IOException e) {
							throw ExceptionsHelper.convertToElastic(e);
						}
						currentDocid = docid;
					}

					@Override
					public boolean execute() {
						final int docVal;
						try {
							docVal = Math.toIntExact(docValues.nextValue());
						} catch (IOException e) {
							throw ExceptionsHelper.convertToElastic(e);
						}

						if (exclude && rBitmap.contains(docVal)) {
							return false;
						}
						else return !include || rBitmap.contains(docVal);
					}
				};
			}
		}
	}
	// end::fast_filter
}
