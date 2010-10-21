/*
 * Copyright 2009 JBoss, a divison Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.bpm.console.client.monitor;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.ListBox;
import org.gwt.mosaic.ui.client.*;
import org.gwt.mosaic.ui.client.layout.*;
import org.gwt.mosaic.ui.client.util.ResizableWidget;
import org.gwt.mosaic.ui.client.util.ResizableWidgetCollection;
import org.jboss.bpm.console.client.common.LoadingOverlay;
import org.jboss.bpm.console.client.util.ConsoleLog;
import org.jboss.bpm.console.client.util.SimpleDateFormat;
import org.jboss.bpm.monitor.gui.client.*;
import org.jboss.errai.bus.client.ErraiBus;
import org.jboss.errai.bus.client.api.Message;
import org.jboss.errai.bus.client.api.MessageCallback;
import org.jboss.errai.bus.client.api.RemoteCallback;
import org.jboss.errai.bus.client.api.base.MessageBuilder;
import org.jboss.errai.workspaces.client.api.ProvisioningCallback;
import org.jboss.errai.workspaces.client.api.WidgetProvider;
import org.jboss.errai.workspaces.client.api.annotations.LoadTool;
import org.timepedia.chronoscope.client.Dataset;
import org.timepedia.chronoscope.client.Datasets;
import org.timepedia.chronoscope.client.Overlay;
import org.timepedia.chronoscope.client.XYPlot;
import org.timepedia.chronoscope.client.browser.ChartPanel;
import org.timepedia.chronoscope.client.browser.Chronoscope;
import org.timepedia.chronoscope.client.browser.json.GwtJsonDataset;
import org.timepedia.chronoscope.client.canvas.View;
import org.timepedia.chronoscope.client.canvas.ViewReadyCallback;
import org.timepedia.chronoscope.client.data.tuple.Tuple2D;
import org.timepedia.chronoscope.client.event.PlotFocusEvent;
import org.timepedia.chronoscope.client.event.PlotFocusHandler;
import org.timepedia.chronoscope.client.util.date.ChronoDate;

import java.text.DateFormat;
import java.util.*;

/**
 * @author: Heiko Braun <hbraun@redhat.com>
 * @date: Mar 11, 2010
 */
@LoadTool(name="Execution History", group = "Processes")
public class ExecutionHistoryView implements WidgetProvider
{

    private static final String TIMEPEDIA_FONTBOOK_SERVICE = "http://api.timepedia.org/fr";

    private static volatile double GOLDEN__RATIO = 1.618;

    private ChartPanel chartPanel;
    private ToolButton menuButton;
    private ToolButton timespanButton;
    private HTML title;
    private HTML timespan;
    private CaptionLayoutPanel chartArea;
    private LayoutPanel timespanPanel;
    private Map<Long, Overlay> overlayMapping = new HashMap<Long, Overlay>();

    private SimpleDateFormat dateFormat = new SimpleDateFormat();

    private String currentProcDef;
    private TimespanValues currentTimespan;

    HTML debugPanel = new HTML();

    public void provideWidget(ProvisioningCallback callback)
    {

        LayoutPanel panel = new LayoutPanel(new BoxLayout(BoxLayout.Orientation.VERTICAL));

        final ToolBar toolBar = new ToolBar();
        panel.add(toolBar, new BoxLayoutData(BoxLayoutData.FillStyle.HORIZONTAL));

        // -----
        
        menuButton = new ToolButton("Open", new ClickHandler()
        {
            public void onClick(ClickEvent clickEvent) {
                selectDefinition();
            }
        });
        toolBar.add(menuButton);
        

        // -----

        title = new HTML();
        title.getElement().setAttribute("style", "font-size:24px; font-weight:BOLD");

        // ------------

        BoxLayout boxLayout = new BoxLayout(BoxLayout.Orientation.HORIZONTAL);
        timespanPanel = new LayoutPanel(boxLayout);
        timespanPanel.setPadding(0);

        timespan = new HTML();
        timespan.getElement().setAttribute("style", "padding-left:10px;padding-top:2px; color:#C8C8C8;font-size:16px;text-align:left;");
        timespanButton = new ToolButton();

        timespanButton.setStyle(ToolButton.ToolButtonStyle.MENU);
        timespanButton.getElement().setAttribute("style", "padding-right:0px;background-image:none;");
        timespanButton.setVisible(false);

        final PopupMenu timeBtnMenu = new PopupMenu();

        for(final TimespanValues ts : TimespanValues.values())
        {
            timeBtnMenu.addItem(ts.getCanonicalName(), new Command()
            {
                public void execute()
                {
                    currentTimespan = ts;
                    loadGraphData(currentProcDef, ts);
                }
            });
        };

        timespanButton.setMenu(timeBtnMenu);

        timespanPanel.add(timespanButton, new BoxLayoutData("20px", "20px"));
        timespanPanel.add(timespan, new BoxLayoutData(BoxLayoutData.FillStyle.HORIZONTAL));

        // ------------

        final LayoutPanel contents = new LayoutPanel(new RowLayout());

        LayoutPanel headerPanel = new LayoutPanel(new ColumnLayout());
        headerPanel.add(title, new ColumnLayoutData("55%"));
        headerPanel.add(timespanPanel, new ColumnLayoutData("45%"));

        // ------------

        chartArea = new CaptionLayoutPanel();
        chartArea.setPadding(15);
        chartArea.setLayout(new BoxLayout(BoxLayout.Orientation.VERTICAL));
        
        contents.add(headerPanel, new RowLayoutData("120"));
        contents.add(chartArea, new RowLayoutData(true));

        // ------------
        panel.add(contents, new BoxLayoutData(BoxLayoutData.FillStyle.BOTH));

        ErraiBus.get().subscribe("process.execution.history", new MessageCallback()
        {
            public void callback(Message message) {
                
                String processName = message.get(String.class, "processName");
                update(processName);

            }
        });

        callback.onSuccess(panel);
    }


    private void selectDefinition()
    {
        HistoryRecords rpcService = MessageBuilder.createCall(
                new RemoteCallback<List<String>>()
                {

                    public void callback(List<String> response)
                    {
                        final LayoutPopupPanel popup = new LayoutPopupPanel(true);
                        popup.addStyleName("soa-PopupPanel");

                        final ListBox listBox = new ListBox();
                        listBox.addItem("");

                        for(String s : response)
                        {
                            listBox.addItem(s);
                        }

                        // show dialogue
                        LayoutPanel p = new LayoutPanel(new BoxLayout(BoxLayout.Orientation.VERTICAL));
                        p.add(new HTML("Which definition would like to inspect?"));
                        p.add(listBox);

                        // -----

                        LayoutPanel p2 = new LayoutPanel(new BoxLayout(BoxLayout.Orientation.HORIZONTAL));
                        p2.add(new Button("Done", new ClickHandler() {
                            public void onClick(ClickEvent clickEvent)
                            {
                                if(listBox.getSelectedIndex()>0)
                                {
                                    popup.hide();
                                    update(listBox.getItemText(listBox.getSelectedIndex()));
                                }
                            }
                        }));

                        // -----

                        HTML html = new HTML("Cancel");
                        html.addClickHandler(new ClickHandler(){
                            public void onClick(ClickEvent clickEvent)
                            {
                                popup.hide();
                            }
                        });
                        p2.add(html, new BoxLayoutData(BoxLayoutData.FillStyle.HORIZONTAL));
                        p.add(p2);

                        // -----

                        popup.setPopupPosition(menuButton.getAbsoluteLeft()-5, menuButton.getAbsoluteTop()+30);
                        popup.setWidget(p);
                        popup.pack();
                        popup.show();

                    }
                },
                HistoryRecords.class
        );

        rpcService.getProcessDefinitionKeys();

    }

    private void update(String procDef) {
        currentProcDef = procDef;

        String name = currentProcDef; // riftsaw name juggling
        String subtitle = "";
        if(currentProcDef.indexOf("}")!=-1)
        {

            String[] qname = currentProcDef.split("}");
            name = qname[1];
            subtitle = qname[0].substring(1, qname[0].length());
        }

        title.setHTML(name + "<br/><div style='color:#C8C8C8;font-size:12px;text-align:left;'>"+subtitle+"</div>");
        loadGraphData(currentProcDef, TimespanValues.LAST_7_DAYS);
    }

    /**
     * Loads the chronoscope data for a particlar processdefinition
     * @param procDefID
     * @param timespan
     */
    private void loadGraphData(final String procDefID, final TimespanValues timespan)
    {
        ChartData rpcService = MessageBuilder.createCall(
                new RemoteCallback<String>()
                {
                    public void callback(String jsonData)
                    {
                        LoadingOverlay.on(chartArea, false);
                        timespanButton.setVisible(true);

                        // feed chronoscope ...
                        final Datasets<Tuple2D> datasets = new Datasets<Tuple2D>();
                        datasets.add(MonitorUI.chronoscope.getDatasetReader().createDatasetFromJson(
                                new GwtJsonDataset(JSOModel.fromJson(jsonData)))
                        );

                        renderChart(datasets);
                        timespanPanel.layout();
                    }
                },
                ChartData.class
        );

        LoadingOverlay.on(chartArea, true);
        rpcService.getCompletedInstances(procDefID, timespan.getCanonicalName());
    }

    private void renderChart(Datasets<Tuple2D> datasets)
    {
        try
        {
            Dataset[] dsArray = datasets.toArray();

            // if exists remove. I don't know how to update at this point
            if(chartPanel!=null)
            {
                //chartArea.remove(chartPanel);
                chartPanel.replaceDatasets(dsArray);
                overlayMapping.clear();
            }
            else
            {
                initChartPanel(dsArray);
            }

            timespan.setText(dsArray[0].getRangeLabel());
            chartArea.layout();
        }
        catch (Exception e)
        {
            ConsoleLog.error("Failed to create chart", e);
        }
    }

    private void initChartPanel(Dataset[] datasets)
    {
        int[] dim = calcChartDimension();

        // ------
        chartPanel = Chronoscope.createTimeseriesChart(datasets, dim[0], dim[1]);

        // marker
        final XYPlot plot = chartPanel.getChart().getPlot();

        plot.addPlotFocusHandler(new PlotFocusHandler(){
            public void onFocus(final PlotFocusEvent event)
            {
                if(event.getFocusDataset()>=0) // zooming
                {
                    ChronoDate chronoDate = ChronoDate.get(event.getDomain());
                    Date date = new Date();
                    date.setTime((long) chronoDate.getTime());
                    
                    StringBuffer sb = new StringBuffer();
                    sb.append("Range: " +event.getRange()).append("<br/>");
                    sb.append("Date: " +date.toString()).append("<br/>");
                    sb.append("TimeZone: ").append(DateTimeFormat.getFormat("Z").format(new Date())).append("<br/>");
                    sb.append("DateF: " +DateTimeFormat.getFormat("yyyy-MM-dd HH:mm:ss z").format(date)).append("<br/>");
                    sb.append("ChronoDate: ").append(chronoDate.getTime()).append("<br/>");

                    debugPanel.setHTML(sb.toString());
                    
                    loadInstances(date);
                }
            }
        });
        
        // ------        

        final ViewReadyCallback callback = new ViewReadyCallback() {
            public void onViewReady(View view) {
                resizeChartArea(view);
            }
        };
        chartPanel.setViewReadyCallback(callback);

        chartArea.add(chartPanel);
        chartArea.add(debugPanel);

        // ------

        ResizableWidgetCollection.get().add(new ResizableWidget() {
            public Element getElement() {
                return chartPanel.getElement();
            }

            public boolean isAttached() {
                return chartPanel.isAttached();
            }

            public void onResize(int width, int height)
            {
                View view = resizeChartView();
            }
        });
    }

    private void loadInstances(Date date)
    {
        ConsoleLog.debug("Loading instances for " +dateFormat.format(date));

        HistoryRecords call = MessageBuilder.createCall(
                new RemoteCallback<Set<String>>()
                {
                    public void callback(Set<String> response) {
                        for(String instanceId : response)
                            ConsoleLog.info("-> "+ instanceId);
                    }
                }, HistoryRecords.class
        );

        
        call.getCompletedInstances(currentProcDef, date.getTime(), currentTimespan.getCanonicalName());
    }

    private int[] calcChartDimension()
    {
        int w = chartArea.getOffsetWidth()/2;
        int h = (int) (w / GOLDEN__RATIO);

        return new int[] {w, h};
    }

    private View resizeChartView()
    {
        int[] dim = calcChartDimension();

        // Resizing the chart once displayed currently unsupported
        final View view = chartPanel.getChart().getView();
        if(view!=null)
            view.resize(dim[0], dim[1]);

        resizeChartArea(view);

        return view;
    }

    private void resizeChartArea(View view)
    {
        /*int resizeTo= Integer.valueOf(view.getHeight()) + 75;
        chartArea.setHeight(resizeTo+"px");
        chartArea.layout();*/
    }
}
