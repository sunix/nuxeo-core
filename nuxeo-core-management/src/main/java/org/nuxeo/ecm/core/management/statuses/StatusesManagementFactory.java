/*
 * (C) Copyright 2010 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 */

package org.nuxeo.ecm.core.management.statuses;

import org.nuxeo.ecm.core.management.CoreManagementComponent;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.management.AbstractResourceFactory;
import org.nuxeo.runtime.management.ObjectNameFactory;

public class StatusesManagementFactory extends AbstractResourceFactory {

    protected void doQualifyNames(ProbeInfo info) {
        info.shortcutName = info.descriptor.getShortcut();
        info.qualifiedName = info.descriptor.getQualifiedName();
        if (info.qualifiedName == null) {
            info.qualifiedName = ObjectNameFactory.formatQualifiedName(CoreManagementComponent.NAME)+",status=probes,probe="+info.shortcutName;
       }
    }


    public void registerResources() {
        AdministrativeStatus adminStatus = Framework.getLocalService(AdministrativeStatus.class);
        service.registerResource("adminStatus", ObjectNameFactory.formatQualifiedName(CoreManagementComponent.NAME)+",status=administrative", AdministrativeStatus.class, adminStatus);
        ProbeRunner runner = Framework.getLocalService(ProbeRunner.class);
        service.registerResource("probeStatus", ObjectNameFactory.formatQualifiedName(CoreManagementComponent.NAME)+",status=probes", ProbeRunner.class, runner);
        for (ProbeInfo info:runner.getProbeInfos()) {
            doQualifyNames(info);
            service.registerResource(info.shortcutName, info.qualifiedName, ProbeInfo.class, info);
        }
    }

}