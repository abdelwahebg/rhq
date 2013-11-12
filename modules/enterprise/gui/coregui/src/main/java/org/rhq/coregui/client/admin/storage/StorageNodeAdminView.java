/*
 * RHQ Management Platform
 * Copyright (C) 2005-2013 Red Hat, Inc.
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
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.coregui.client.admin.storage;

import java.util.Map;

import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.tab.events.TabSelectedEvent;
import com.smartgwt.client.widgets.tab.events.TabSelectedHandler;

import org.rhq.coregui.client.BookmarkableView;
import org.rhq.coregui.client.CoreGUI;
import org.rhq.coregui.client.IconEnum;
import org.rhq.coregui.client.LinkManager;
import org.rhq.coregui.client.UserSessionManager;
import org.rhq.coregui.client.ViewPath;
import org.rhq.coregui.client.admin.AdministrationView;
import org.rhq.coregui.client.components.tab.NamedTab;
import org.rhq.coregui.client.components.tab.NamedTabSet;
import org.rhq.coregui.client.components.view.ViewName;
import org.rhq.coregui.client.dashboard.AutoRefreshUtil;
import org.rhq.coregui.client.gwt.GWTServiceLookup;
import org.rhq.coregui.client.inventory.AutoRefresh;
import org.rhq.coregui.client.util.Log;
import org.rhq.coregui.client.util.StringUtility;
import org.rhq.coregui.client.util.enhanced.EnhancedVLayout;

/**
 * The main view for managing storage nodes.
 *
 * @author Jirka Kremser
 */
public class StorageNodeAdminView extends EnhancedVLayout implements BookmarkableView, AutoRefresh {

    public static final ViewName VIEW_ID = new ViewName("StorageNodes", MSG.view_adminTopology_storageNodes(),
        IconEnum.STORAGE_NODE);

    public static final String VIEW_PATH = AdministrationView.VIEW_ID + "/"
        + AdministrationView.SECTION_TOPOLOGY_VIEW_ID + "/" + VIEW_ID;
        
    private final NamedTabSet tabset;
    private TabInfo tableTabInfo = new TabInfo(0, new ViewName("Nodes"));
    private TabInfo settingsTabInfo = new TabInfo(1, new ViewName("Settings", "Cluster Settings"));
    private TabInfo alertsTabInfo = new TabInfo(2, new ViewName("Alerts", "Cluster Alerts"));
    private TabInfo backupTabInfo = new TabInfo(3, new ViewName("Backup"));
    private StorageNodeTableView table;
    private final NamedTab alerts;
    
    private Timer refresher;
    private volatile boolean isRefreshing;

    private Map<Integer, Integer> resIdsToStorageNodeIdsMap;

    public StorageNodeAdminView() {
        super();
        setHeight100();
        setWidth100();
        setLayoutTopMargin(8);
        tabset = new NamedTabSet();
        NamedTab table = new NamedTab(tableTabInfo.name);
        table.addTabSelectedHandler(new TabSelectedHandler() {
            public void onTabSelected(TabSelectedEvent event) {
                CoreGUI.goToView(VIEW_PATH);
            }
        });

        NamedTab settings = new NamedTab(settingsTabInfo.name);
        settings.addTabSelectedHandler(new TabSelectedHandler() {
            public void onTabSelected(TabSelectedEvent event) {
                CoreGUI.goToView(VIEW_PATH + "/" + settingsTabInfo.name);
            }
        });

        alerts = new NamedTab(alertsTabInfo.name);
        alerts.addTabSelectedHandler(new TabSelectedHandler() {
            public void onTabSelected(TabSelectedEvent event) {
                CoreGUI.goToView(VIEW_PATH + "/" + alertsTabInfo.name);
            }
        });
        
        final NamedTab backup = new NamedTab(backupTabInfo.name);
        backup.addTabSelectedHandler(new TabSelectedHandler() {
            public void onTabSelected(TabSelectedEvent event) {
                CoreGUI.goToView(VIEW_PATH + "/" + backupTabInfo.name);
            }
        });

        tabset.setTabs(table, settings, alerts/*, backup*/);
        addMember(tabset);
    }

    private void showTab(final TabInfo tabInfo) {
        if (tabInfo.equals(tableTabInfo)) {
            table = new StorageNodeTableView();
            tabset.getTabByName(tabInfo.name.getName()).setPane(table);
            tabset.selectTab(tabInfo.index);
        } else if (tabInfo.equals(backupTabInfo)) {
            tabset.getTabByName(tabInfo.name.getName()).setPane(new Label("in progress.."));
        } else if (tabInfo.equals(alertsTabInfo)) {
            if (resIdsToStorageNodeIdsMap != null) {
                tabset.getTabByName(tabInfo.name.getName()).setPane(
                    new StorageNodeAlertHistoryView("storageNodesAlerts", resIdsToStorageNodeIdsMap));
            } else {
                GWTServiceLookup.getStorageService().findResourcesWithAlertDefinitions(
                    new AsyncCallback<Map<Integer, Integer>>() {
                        @Override
                        public void onFailure(Throwable caught) {
                            if (!UserSessionManager.isLoggedOut()) {
                                CoreGUI.getErrorHandler().handleError(
                                    "Unable to render storage node alert view: " + caught.getMessage(), caught);
                            }
                        }

                        @Override
                        public void onSuccess(Map<Integer, Integer> result) {
                            if (result == null || result.size() == 0) {
                                onFailure(new Exception(
                                    "Unfortunately, there are no associated resources for the available storage nodes. "
                                        + "Check if the agents are running on the machines where the storage nodes are deployed."));
                            } else {
                                resIdsToStorageNodeIdsMap = result;
                                tabset.getTabByName(tabInfo.name.getName()).setPane(
                                    new StorageNodeAlertHistoryView("storageNodesAlerts", resIdsToStorageNodeIdsMap));
                                tabset.selectTab(tabInfo.index);
                            }
                        }
                    });
            }
        } else if (tabInfo.equals(settingsTabInfo)) {
            ClusterConfigurationEditor editor = new ClusterConfigurationEditor(false);
            tabset.getTabByName(tabInfo.name.getName()).setPane(editor);
            tabset.selectTab(tabInfo.index);
        }
    }
    
    public static String getAlertsString(String prefix, int numOfUnackAlerts) {
        return getAlertsString(prefix, -1, numOfUnackAlerts);
    }

    public static String getAlertsString(String prefix, int storageNodeId, int numOfUnackAlerts) {
        String detailsUrl = "#" + VIEW_PATH + "/" + storageNodeId + "/Alerts";
        detailsUrl = StringUtility.escapeHtml(detailsUrl);
        String value = prefix
            + (numOfUnackAlerts != 0 ? " <span style='color: #CC0000;'>(" + numOfUnackAlerts + ")</span>" : " ("
                + numOfUnackAlerts + ")");
        return storageNodeId == -1 ? value : LinkManager.getHref(detailsUrl, value);
    }
    
    private static final class TabInfo {
        private int index;
        private ViewName name;

        private TabInfo(int index, ViewName name) {
            this.index = index;
            this.name = name;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + index;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TabInfo other = (TabInfo) obj;
            if (index != other.index)
                return false;
            return true;
        }
    }

    @Override
    public void renderView(ViewPath viewPath) {
        if (viewPath.getViewPath().size() == 3) {
            showTab(tableTabInfo);
        } else {
            String viewId = viewPath.getCurrent().getPath();
            if (settingsTabInfo.name.getName().equals(viewId)) {
                showTab(settingsTabInfo);
            } else if (alertsTabInfo.name.getName().equals(viewId)) {
                showTab(alertsTabInfo);
            } else if (backupTabInfo.name.getName().equals(viewId)) {
                showTab(backupTabInfo);
            } else { //default
                showTab(tableTabInfo);
                table.renderView(viewPath);
            }
        }
    }
    
    private void refreshNotAcknowledgedStorageNodeAlertsCount() {
        isRefreshing = true;
        GWTServiceLookup.getStorageService().findNotAcknowledgedStorageNodeAlertsCount(new AsyncCallback<Integer>() {
            @Override
            public void onSuccess(Integer result) {
                Log.info("Running the task for fetching the number of ALL unack alerts...");
                alerts.setTitle(StorageNodeAdminView.getAlertsString(alerts.getTitle(), result));
                isRefreshing = false;
            }

            @Override
            public void onFailure(Throwable caught) {
                if (!UserSessionManager.isLoggedOut()) {
                    Log.error("Unable to fetch the unack alerts: " + caught.getMessage());
                }
            }
        });
    }

    @Override
    public void startRefreshCycle() {
        Log.info("Scheduling the repetitive task for fetching the number of ALL unack alerts...");
        refresher = AutoRefreshUtil.startRefreshCycle(this, this, refresher, 15000);
    }

    @Override
    public boolean isRefreshing() {
        return isRefreshing;
    }

    @Override
    public void refresh() {
        refreshNotAcknowledgedStorageNodeAlertsCount();
    }
    
    @Override
    protected void onDraw() {
        super.onDraw();
        startRefreshCycle();
    }
    
    @Override
    protected void onDestroy() {
        stopRefreshing();
        super.onDestroy();
    }
    
    private void stopRefreshing() {
        Log.info("Unscheduling the repetitive task for fetching the number of ALL unack alerts...");
        AutoRefreshUtil.onDestroy(refresher);
        isRefreshing = false;
    }
}