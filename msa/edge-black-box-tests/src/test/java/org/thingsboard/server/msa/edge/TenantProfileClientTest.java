/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
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
package org.thingsboard.server.msa.edge;

import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Test;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.TenantProfile;
import org.thingsboard.server.msa.AbstractContainerTest;

import java.util.concurrent.TimeUnit;

@Slf4j
public class TenantProfileClientTest extends AbstractContainerTest {

    @Test
    public void testTenantProfileUpdate() {
        cloudRestClient.login("sysadmin@thingsboard.org", "sysadmin");

        // get edge tenant and tenant profile
        Tenant tenant = cloudRestClient.getTenantById(edge.getTenantId()).get();
        TenantProfile tenantProfile = cloudRestClient.getTenantProfileById(tenant.getTenantProfileId()).get();

        // update tenant profile
        tenantProfile.setDescription("Updated Tenant Profile Description");
        cloudRestClient.saveTenantProfile(tenantProfile);

        Awaitility.await()
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> {
                    Tenant edgeTenant = edgeRestClient.getTenantById(edge.getTenantId()).get();
                    return edgeTenant.getTenantProfileId().equals(cloudRestClient.getTenantProfileById(tenantProfile.getId()).get().getId());
                });

        cloudRestClient.login("tenant@thingsboard.org", "tenant");
    }
}
