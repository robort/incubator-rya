/*
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
package org.apache.rya.indexing.pcj.fluo.visibility;

import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;

import org.apache.accumulo.core.security.Authorizations;
import org.apache.rya.accumulo.AccumuloRdfConfiguration;
import org.apache.rya.accumulo.AccumuloRyaDAO;
import org.apache.rya.api.RdfCloudTripleStoreConfiguration;
import org.apache.rya.api.domain.RyaStatement;
import org.apache.rya.api.resolver.RdfToRyaConversions;
import org.apache.rya.indexing.accumulo.ConfigUtils;
import org.apache.rya.indexing.pcj.fluo.ITBase;
import org.apache.rya.indexing.pcj.fluo.api.CreatePcj;
import org.apache.rya.indexing.pcj.storage.PrecomputedJoinStorage;
import org.apache.rya.indexing.pcj.storage.accumulo.AccumuloPcjStorage;
import org.junit.Assert;
import org.junit.Test;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.BindingSet;
import org.openrdf.query.impl.BindingImpl;

import com.google.common.collect.Sets;

/**
 * Performs integration tests over the Fluo application geared towards various types of input.
 * <p>
 * These tests are being ignore so that they will not run as unit tests while building the application.
 */
public class HistoricStreamingVisibilityIT extends ITBase {

    /**
     * Ensure historic matches are included in the result.
     */
    @Test
    public void historicResults() throws Exception {
        // A query that finds people who talk to Eve and work at Chipotle.
        final String sparql =
              "SELECT ?x " +
                "WHERE { " +
                "?x <http://talksTo> <http://Eve>. " +
                "?x <http://worksAt> <http://Chipotle>." +
              "}";
        
        accumuloConn.securityOperations().changeUserAuthorizations(ACCUMULO_USER, new Authorizations("U","V","W"));
        AccumuloRyaDAO dao = new AccumuloRyaDAO();
        dao.setConnector(accumuloConn);
        dao.setConf(makeConfig());
        dao.init();

        // Triples that are loaded into Rya before the PCJ is created.
        final Set<RyaStatement> historicTriples = Sets.newHashSet(
                makeRyaStatement(makeStatement("http://Alice", "http://talksTo", "http://Eve"),"U"),
                makeRyaStatement(makeStatement("http://Bob", "http://talksTo", "http://Eve"),"V"),
                makeRyaStatement(makeStatement("http://Charlie", "http://talksTo", "http://Eve"),"W"),

                makeRyaStatement(makeStatement("http://Eve", "http://helps", "http://Kevin"), "U"),

                makeRyaStatement(makeStatement("http://Bob", "http://worksAt", "http://Chipotle"), "W"),
                makeRyaStatement(makeStatement("http://Charlie", "http://worksAt", "http://Chipotle"), "V"),
                makeRyaStatement(makeStatement("http://Eve", "http://worksAt", "http://Chipotle"), "U"),
                makeRyaStatement(makeStatement("http://David", "http://worksAt", "http://Chipotle"), "V"));

        dao.add(historicTriples.iterator());
        dao.flush();
        
        // The expected results of the SPARQL query once the PCJ has been computed.
        final Set<BindingSet> expected = new HashSet<>();
        expected.add(makeBindingSet(
                new BindingImpl("x", new URIImpl("http://Bob"))));
        expected.add(makeBindingSet(
                new BindingImpl("x", new URIImpl("http://Charlie"))));
        
        // Create the PCJ table.
        final PrecomputedJoinStorage pcjStorage = new AccumuloPcjStorage(accumuloConn, RYA_INSTANCE_NAME);
        final String pcjId = pcjStorage.createPcj(sparql);

        new CreatePcj().withRyaIntegration(pcjId, pcjStorage, fluoClient, accumuloConn, RYA_INSTANCE_NAME);

        // Verify the end results of the query match the expected results.
        fluo.waitForObservers();
        Set<BindingSet> results = Sets.newHashSet(pcjStorage.listResults(pcjId));
        Assert.assertEquals(expected, results);
    }
    
    
    private AccumuloRdfConfiguration makeConfig() {
        final AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
        conf.setTablePrefix(RYA_INSTANCE_NAME);
        // Accumulo connection information.
        conf.set(ConfigUtils.CLOUDBASE_USER, ACCUMULO_USER);
        conf.set(ConfigUtils.CLOUDBASE_PASSWORD, ACCUMULO_PASSWORD);
        conf.set(ConfigUtils.CLOUDBASE_INSTANCE, instanceName);
        conf.set(ConfigUtils.CLOUDBASE_ZOOKEEPERS, zookeepers);
        conf.set(RdfCloudTripleStoreConfiguration.CONF_QUERY_AUTH, "U,V,W");

        return conf;
    }
    
    
    private static RyaStatement makeRyaStatement(Statement statement, String visibility) throws UnsupportedEncodingException {
    	
    	RyaStatement ryaStatement = RdfToRyaConversions.convertStatement(statement);
    	ryaStatement.setColumnVisibility(visibility.getBytes("UTF-8"));
    	return ryaStatement;
    	
    }


}
