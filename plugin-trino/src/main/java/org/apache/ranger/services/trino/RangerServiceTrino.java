/*
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
package org.apache.ranger.services.trino;

import org.apache.commons.lang.StringUtils;
import org.apache.ranger.plugin.client.HadoopConfigHolder;
import org.apache.ranger.plugin.client.HadoopException;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.service.RangerBaseService;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import org.apache.ranger.services.trino.client.TrinoResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RangerServiceTrino
        extends RangerBaseService {
    private static final Logger LOG = LoggerFactory.getLogger(RangerServiceTrino.class);

    public static final String ACCESS_TYPE_SELECT = "select";

    @Override
    public Map<String, Object> validateConfig() {
        Map<String, Object> ret         = new HashMap<>();
        String              serviceName = getServiceName();

        LOG.debug("RangerServiceTrino.validateConfig(): Service: {}", serviceName);

        if (configs != null) {
            try {
                if (!configs.containsKey(HadoopConfigHolder.RANGER_LOGIN_PASSWORD)) {
                    configs.put(HadoopConfigHolder.RANGER_LOGIN_PASSWORD, null);
                }

                ret = TrinoResourceManager.connectionTest(serviceName, configs);
            } catch (HadoopException he) {
                LOG.error("<== RangerServiceTrino.validateConfig() Error:{}", String.valueOf(he));

                throw he;
            }
        }

        LOG.debug("RangerServiceTrino.validateConfig(): Response: {}", ret);

        return ret;
    }

    @Override
    public List<String> lookupResource(ResourceLookupContext context)
            throws Exception {
        List<String>        ret         = new ArrayList<>();
        String              serviceName = getServiceName();
        String              serviceType = getServiceType();
        Map<String, String> configs     = getConfigs();

        LOG.debug("==> RangerServiceTrino.lookupResource() Context: ({})", context);

        if (context != null) {
            try {
                if (!configs.containsKey(HadoopConfigHolder.RANGER_LOGIN_PASSWORD)) {
                    configs.put(HadoopConfigHolder.RANGER_LOGIN_PASSWORD, null);
                }

                ret = TrinoResourceManager.getTrinoResources(serviceName, serviceType, configs, context);
            } catch (Exception e) {
                LOG.error("<==RangerServiceTrino.lookupResource() Error : {}", String.valueOf(e));

                throw e;
            }
        }

        LOG.debug("<== RangerServiceTrino.lookupResource() Response: ({})", ret);

        return ret;
    }

    @Override
    public List<RangerPolicy> getDefaultRangerPolicies()
            throws Exception {
        LOG.debug("==> RangerServiceTrino.getDefaultRangerPolicies()");

        List<RangerPolicy> ret = super.getDefaultRangerPolicies();

        for (RangerPolicy defaultPolicy : ret) {
            if (defaultPolicy.getName().contains("all") && StringUtils.isNotBlank(lookUpUser)) {
                List<RangerPolicyItemAccess> accessListForLookupUser = new ArrayList<>();
                RangerPolicyItem             policyItemForLookupUser = new RangerPolicyItem();

                accessListForLookupUser.add(new RangerPolicyItemAccess(ACCESS_TYPE_SELECT));

                policyItemForLookupUser.setUsers(Collections.singletonList(lookUpUser));
                policyItemForLookupUser.setAccesses(accessListForLookupUser);
                policyItemForLookupUser.setDelegateAdmin(false);

                List<RangerPolicyItem> policyItems = defaultPolicy.getPolicyItems();

                if (policyItems == null || policyItems.isEmpty()) {
                    policyItems = new ArrayList<>();
                }

                policyItems.add(policyItemForLookupUser);

                defaultPolicy.setPolicyItems(policyItems);
            }
        }

        LOG.debug("<== RangerServiceTrino.getDefaultRangerPolicies()");

        return ret;
    }
}
