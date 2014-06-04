/*
 * RHQ Management Platform
 * Copyright (C) 2005-2014 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
 */

package org.rhq.modules.plugins.jbossas7;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.LogFactory;

import org.rhq.core.pluginapi.inventory.DiscoveredResourceDetails;
import org.rhq.core.pluginapi.inventory.ResourceDiscoveryContext;

/**
 * Discover subsystems. We need to distinguish two cases denoted by the path
 * plugin config:
 * <ul>
 *     <li>Path is a single 'word': here the value denotes a key in the resource path
 *     of AS7, that identifies a child type see e.g. the Connectors below the JBossWeb
 *     service in the plugin descriptor. There can be multiple resources of the given
 *     type. In addition it is possible that a path entry in configuration shares multiple
 *     types that are separated by the pipe symbol.</li>
 *     <li>Path is a key-value pair (e.g. subsystem=web ). This denotes a singleton
 *     subsystem with a fixes path within AS7 (perhaps below another resource in the
 *     tree.</li>
 * </ul>
 *
 * This subclass adds logic for discovering different versions of the same logical resource,
 * by stripping version info out of the path, removing it from the resourceName and setting it
 * as the resourceVersion.
 * <p/>
 * The default version matching pattern is designed to match maven-like version stamping:
 * <code>name-version.ext</code> being the basic format.  The version must minimally contain
 * <code>major.minor</code> values.  See Maven documentation for more information.  The default
 * pattern is <code>"^(.*?)-([0-9]+\\.[0-9].*)(\\..*)$"</code>. The same pattern is applied to
 * all <code>Deployment</code> and <code>Subdeployment</code> artifacts.
 * <p/>
 * To override the default pattern the following environment variable can be defined:
 * <code>rhq.as7.VersionedSubsystemDiscovery.pattern=theDesiredRegexPattern</code>. The regex
 * *must* capture three groups as does the default. Group1=name, Group2=version, Group3=extension.
 *
 * @author Jay Shaughnessy
 */
public class VersionedSubsystemDiscovery extends SubsystemDiscovery {

    /* The matched format is name-VERSION.ext.  Version must minimally be in major.minor format.  Simpler versions,
     * like a single digit, are too possibly part of the actual name.  myapp-1.war and myapp-2.war could easily be
     * different apps (albeit poorly named).  But myapp-1.0.war and myapp-2.0.war are pretty clearly versions of
     * the same app.  The goal was to handle maven-style versioning.
     */
    static private final String PATTERN_DEFAULT = "^(.*?)-([0-9]+\\.[0-9].*)(\\..*)$";
    static private final String PATTERN_PROP = "rhq.as7.VersionedSubsystemDiscovery.pattern";
    static private final Matcher MATCHER;
    static private final String SUBDEPLOYMENT_TYPE = "Subdeployment";

    static {
        Matcher m = null;
        try {
            String override = System.getProperty(PATTERN_PROP);
            if (null != override) {
                Pattern p = Pattern.compile(override);
                m = p.matcher("");
                if (m.groupCount() != 3) {
                    String msg = "Pattern supplied by system property [" + PATTERN_PROP
                        + "] is invalid. Expected [3] matching groups but found [" + m.groupCount()
                        + "]. Will use default pattern [" + PATTERN_DEFAULT + "].";
                    m = null;
                    LogFactory.getLog(VersionedSubsystemDiscovery.class).error(msg);
                }
            }
        } catch (Exception e) {
            String msg = "Pattern supplied by system property [" + PATTERN_PROP
                + "] is invalid. Will use default pattern [" + PATTERN_DEFAULT + "].";
            m = null;
            LogFactory.getLog(VersionedSubsystemDiscovery.class).error(msg, e);
        }

        MATCHER = (null != m) ? m : Pattern.compile(PATTERN_DEFAULT).matcher("");
    }

    //private final Log log = LogFactory.getLog(this.getClass());

    public Set<DiscoveredResourceDetails> discoverResources(ResourceDiscoveryContext<BaseComponent<?>> context)
        throws Exception {

        // Perform the standard discovery. This can return resources with versions in the name,
        // key and path.
        Set<DiscoveredResourceDetails> details = super.discoverResources(context);

        // Now, post-process the discovery as needed.  We need to strip the versions from the
        // resource name and the resource key.  We want to leave them in the path plugin config
        // property, that value reflects the actual DMR used to query EAP.
        // The stripped versions are then used to set the resource version string.

        for (DiscoveredResourceDetails detail : details) {
            MATCHER.reset(detail.getResourceName());
            if (MATCHER.matches()) {
                // reset the resource name with the stripped value
                detail.setResourceName(MATCHER.group(1) + MATCHER.group(3));

                // The version string for a subdeployment must incorporate the parent deployment's version
                // so that we detect an overall version change if the parent is re-deployed.  Without this
                // the Subdeployment will not be properly updated if its version remains unchanged in the
                // updated Deployment.
                if (SUBDEPLOYMENT_TYPE.equals(context.getResourceType().getName())) {
                    BaseComponent parentComponent = context.getParentResourceComponent();
                    String parentPath = parentComponent.getPath();
                    String parentResourceVersion = (parentPath.isEmpty()) ? "" : (context.getParentResourceContext()
                        .getVersion() + "/");
                    detail.setResourceVersion(parentResourceVersion + MATCHER.group(2));
                } else {
                    detail.setResourceVersion(MATCHER.group(2));
                }
            }
            StringBuilder sb = new StringBuilder();
            String comma = "";
            for (String segment : detail.getResourceKey().split(",")) {
                sb.append(comma);
                comma = ",";
                MATCHER.reset(segment);
                if (MATCHER.matches()) {
                    sb.append(MATCHER.group(1) + MATCHER.group(3));
                } else {
                    sb.append(segment);
                }
            }
            detail.setResourceKey(sb.toString());
        }

        return details;
    }
}
