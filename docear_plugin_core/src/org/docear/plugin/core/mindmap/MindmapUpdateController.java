package org.docear.plugin.core.mindmap;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.SwingUtilities;

import org.docear.plugin.core.MapItem;
import org.docear.plugin.core.features.DocearMapModelExtension;
import org.docear.plugin.core.features.MapModificationSession;
import org.docear.plugin.core.ui.SwingWorkerDialog;
import org.docear.plugin.core.workspace.model.DocearWorkspaceProject;
import org.freeplane.core.util.LogUtils;
import org.freeplane.core.util.TextUtils;
import org.freeplane.features.map.INodeView;
import org.freeplane.features.map.MapChangeEvent;
import org.freeplane.features.map.MapModel;
import org.freeplane.features.mode.Controller;
import org.freeplane.features.url.UrlManager;
import org.freeplane.features.url.mindmapmode.MFileManager;
import org.freeplane.plugin.workspace.WorkspaceController;
import org.freeplane.plugin.workspace.model.project.AWorkspaceProject;
import org.freeplane.view.swing.map.MapView;
import org.freeplane.view.swing.map.NodeView;
import org.jdesktop.swingworker.SwingWorker;

public class MindmapUpdateController {
	private final MapModificationSession session = new MapModificationSession();
	private final ArrayList<AMindmapUpdater> updaters = new ArrayList<AMindmapUpdater>();
	private boolean showDialog = true;
	
	public MindmapUpdateController(){}
	
	public MindmapUpdateController(boolean showDialog){
		this.showDialog = showDialog;
	}

	public void addMindmapUpdater(AMindmapUpdater updater) {
		this.updaters.add(updater);
	}

	public List<AMindmapUpdater> getMindmapUpdaters() {
		return this.updaters;
	}

	public boolean updateAllMindmapsInCurrentMapsProject() {
		AWorkspaceProject project = WorkspaceController.getMapProject();
		if (project != null) {
			return updateAllMindmapsInProject(project);
		}
		return false;
	}
	
	public boolean updateAllMindmapsInWorkspace() {
		return updateAllMindmapsInProject(WorkspaceController.getCurrentModel().getProjects().toArray(new AWorkspaceProject[0]));		
	}
	
	public boolean updateAllMindmapsInProject(AWorkspaceProject... projects) {
		List<MapItem> maps = new ArrayList<MapItem>();
		
		for (AWorkspaceProject project : projects) {
    		if(!DocearWorkspaceProject.isCompatible(project)) {
    			return false;
    		}    		
    		for (URI uri : project.getModel().getAllNodesFiltered(".mm")) {
    			maps.add(new MapItem(uri));
    		}
		}
		
		return updateMindmaps(maps);
	}
		
	public boolean updateRegisteredMindmapsInProject() {
		return updateRegisteredMindmapsInProject(false);
	}
	
	public boolean updateRegisteredMindmapsInProject(boolean openMindmapsToo, AWorkspaceProject... projects) {
		List<MapItem> maps = new ArrayList<MapItem>(); 
		
		if (projects==null || projects.length==0) {
			projects = new AWorkspaceProject[] {WorkspaceController.getMapProject()};
		}
		
		if (projects.length == 0) {
			return false;
		}
		
		for (AWorkspaceProject project : projects) {
    		if (project != null) {
        		if(DocearWorkspaceProject.isCompatible(project)) {
        			for(URI mapUri : ((DocearWorkspaceProject)project).getLibraryMaps()) {
        				maps.add(new MapItem(mapUri));
        			}
        		}
        		if (openMindmapsToo) {
        			for (MapItem item : getAllOpenMaps()) {
        				maps.add(item);
        			}
        		}
    		}
		}
		
		return updateMindmaps(maps);
	}
	
	public boolean updateRegisteredMindmapsInWorkspace(boolean openMindmapsToo) {
		return updateRegisteredMindmapsInProject(openMindmapsToo, WorkspaceController.getCurrentModel().getProjects().toArray(new AWorkspaceProject[0]));		
	}

	public boolean updateOpenMindmaps() {
		List<MapItem> maps = getAllOpenMaps();

		return updateMindmaps(maps);
	}

	private List<MapItem> getAllOpenMaps() {
		List<MapItem> maps = new ArrayList<MapItem>();
		Map<String, MapModel> openMaps = Controller.getCurrentController().getMapViewManager().getMaps();
		for (String name : openMaps.keySet()) {
			maps.add(new MapItem(openMaps.get(name)));
		}
		return maps;
	}

	public boolean updateCurrentMindmap() {
		return updateCurrentMindmap(false);
	}

	public boolean updateCurrentMindmap(boolean closeWhenDone) {
		List<MapItem> maps = new ArrayList<MapItem>();
		
		try {
			maps.add(new MapItem(Controller.getCurrentController().getMap()));
		}
		catch (NullPointerException e) {			
		}

		Controller.getCurrentController().getMap().setSaved(false);

		return updateMindmaps(maps, closeWhenDone);
	}

	public boolean updateMindmapsInList(List<MapModel> maps) {
		List<MapItem> mapItems = new ArrayList<MapItem>();

		for (MapModel map : maps) {
			try {
				mapItems.add(new MapItem(map));
			}
			catch (NullPointerException e) {				
			}
		}

		return updateMindmaps(mapItems);

	}

	public boolean updateMindmaps(List<MapItem> uris) {
		return updateMindmaps(uris, false);
	}

	public boolean updateMindmaps(List<MapItem> maps, boolean closeWhenDone) {
		final SwingWorker<Void, Void> thread = getUpdateThread(maps, closeWhenDone);
		if(showDialog){
			SwingWorkerDialog workerDialog = new SwingWorkerDialog(Controller.getCurrentController().getViewController().getJFrame());
			workerDialog.setHeadlineText(TextUtils.getText("updating_mindmaps_headline"));
			workerDialog.setSubHeadlineText(TextUtils.getText("updating_mindmaps_subheadline"));
			workerDialog.showDialog(thread);
			workerDialog = null;
		}
		else{
			final ExecutorService executor = Executors.newSingleThreadExecutor();			
			thread.addPropertyChangeListener(new PropertyChangeListener() {
				
				public void propertyChange(PropertyChangeEvent evt) {
					if(evt.getPropertyName().equals(SwingWorkerDialog.IS_CANCELED) || evt.getPropertyName().equals(SwingWorkerDialog.IS_DONE)){
						if(thread != null){
							thread.cancel(true);							
						}
						if(executor != null){			
							executor.shutdownNow();							
						}
					}
				}
			});
			executor.execute(thread);
		}

		return !thread.isCancelled();
	}

	public SwingWorker<Void, Void> getUpdateThread(final List<MapItem> maps) {
		return getUpdateThread(maps, false);
	}

	public SwingWorker<Void, Void> getUpdateThread(final List<MapItem> maps, final boolean closeWhenDone) {

		return new SwingWorker<Void, Void>() {
			private int totalCount;
			private boolean mapHasChanged = false;

			private final long start = System.currentTimeMillis();

			@Override
			protected Void doInBackground() throws Exception {
				try {
					if (maps == null || maps.size() == 0) {
						return null;
					}
					NodeView.setModifyModelWithoutRepaint(true);
					MapView.setNoRepaint(true);
					fireStatusUpdate(SwingWorkerDialog.SET_PROGRESS_BAR_INDETERMINATE, null, null);
					fireStatusUpdate(SwingWorkerDialog.PROGRESS_BAR_TEXT, null, TextUtils.getText("computing_node_count"));
					totalCount = maps.size()*getMindmapUpdaters().size();
					if (canceled())
						return null;
					fireStatusUpdate(SwingWorkerDialog.SET_PROGRESS_BAR_DETERMINATE, null, null);
					int count = 0;
					fireProgressUpdate(100 * count / totalCount);
					AWorkspaceProject workingProject = WorkspaceController.getMapProject();

					for (AMindmapUpdater updater : getMindmapUpdaters()) {
						count++;
						fireStatusUpdate(SwingWorkerDialog.PROGRESS_BAR_TEXT, null, updater.getTitle());
						if (canceled())
							return null;
						for (MapItem mapItem : maps) {							
							mapHasChanged = false; 
							MapModel map = null;
							try {
							 map = mapItem.getModel();
							 if (map==null || map.isReadOnly()) {								
								 continue;
							 }
							AWorkspaceProject project = WorkspaceController.getMapProject(map);
							if(!workingProject.equals(project)) {
								fireStatusUpdate(SwingWorkerDialog.DETAILS_LOG_TEXT, null,  TextUtils.format("docear.map.ignore.otherproject", map.getTitle(), map.getFile()));
								continue;
							}
							if(project == null) {
								fireStatusUpdate(SwingWorkerDialog.DETAILS_LOG_TEXT, null,  TextUtils.format("docear.map.ignore.noproject", map.getTitle(), map.getFile()));
								continue;
							}
							if(!project.isLoaded()) {
								fireStatusUpdate(SwingWorkerDialog.DETAILS_LOG_TEXT, null,  TextUtils.format("docear.map.ignore.projectnotloaded", map.getTitle(), map.getFile()));
								continue;
							}
							if(!DocearWorkspaceProject.isCompatible(project)) {
								fireStatusUpdate(SwingWorkerDialog.DETAILS_LOG_TEXT, null, TextUtils.format("docear.map.ignore.wrongversion", map.getTitle(), map.getFile()));
								continue;
							}
							 
							 map.getExtension(DocearMapModelExtension.class).setMapModificationSession(session);
							} 
							catch (Exception ex) {
								LogUtils.warn("MindmapUpdateController$SwingWorker.doInBackground().1 "+ex.getMessage()+" ("+mapItem.getIdentifierForDialog()+")");
							}
							
							fireStatusUpdate(SwingWorkerDialog.DETAILS_LOG_TEXT, null,
									updater.getTitle()+": " + mapItem.getIdentifierForDialog());
							fireStatusUpdate(SwingWorkerDialog.SET_SUB_HEADLINE, null, TextUtils.getText("updating_against_p1")
									+ getMapTitle(map) + TextUtils.getText("updating_against_p2"));							
							this.mapHasChanged = updater.updateMindmap(map);
							fireProgressUpdate(100 * count / totalCount);
							if (this.mapHasChanged) {
								if (!mapItem.isMapOpen()) {
									saveMap(map);
									MapChangeEvent event = new MapChangeEvent(this, UrlManager.MAP_URL, map.getURL(), null);
									Controller.getCurrentModeController().getMapController().fireMapChanged(event);
									map.destroy();
								}
								else {
									map.setSaved(false);
									map.setReadOnly(false);
								}
							}

						}
					}

					fireStatusUpdate(SwingWorkerDialog.SET_SUB_HEADLINE, null, TextUtils.getText("updating_mapviews"));
					fireStatusUpdate(SwingWorkerDialog.PROGRESS_BAR_TEXT, null, TextUtils.getText("updating_mapviews"));
				}
				catch (InterruptedException e) {
					LogUtils.info("MindmapUpdateController aborted.");
				}
				catch (Exception e) {
					LogUtils.warn(e);
				}
				return null;
			}

			

			private String getMapTitle(MapModel map) {
				String mapTitle = "";
					if (map.getFile() != null) {
					mapTitle = map.getFile().getName();
				}
				else {
					mapTitle = map.getTitle();
				}
				return mapTitle;
			}

			protected void done() {
				NodeView.setModifyModelWithoutRepaint(false);
				MapView.setNoRepaint(false);
				for (MapItem item : maps) {
					if (item.isMapOpen()) {
						LogUtils.info("updating view for map: " + item.getIdentifierForDialog());
						long l = System.currentTimeMillis();
						for(INodeView nodeView : item.getModel().getRootNode().getViewers()) {
							if(nodeView instanceof NodeView) {
								((NodeView) nodeView).updateAll();								
							}
						}
						LogUtils.info("resetting folding complete: "+(System.currentTimeMillis()-l));
					}
				}
				
				if (this.isCancelled() || Thread.currentThread().isInterrupted()) {
					this.firePropertyChange(SwingWorkerDialog.IS_DONE, null, TextUtils.getText("update_canceled"));
				}
				else {
					this.firePropertyChange(SwingWorkerDialog.SET_PROGRESS_BAR_DETERMINATE, null, null);
					this.firePropertyChange(SwingWorkerDialog.IS_DONE, null, TextUtils.getText("update_complete"));
				}

				if (closeWhenDone) {
					try {
						this.firePropertyChange(SwingWorkerDialog.CLOSE, null, null);
					}
					catch (Exception e) {
						e.printStackTrace();
					}
				}
				else {
					long time = System.currentTimeMillis() - this.start;
					this.firePropertyChange(SwingWorkerDialog.DETAILS_LOG_TEXT, null, TextUtils.getText("execution_time") + " "
							+ time + " ms");
				}

			}

			private boolean canceled() throws InterruptedException {
				Thread.sleep(1L);
				return (this.isCancelled() || Thread.currentThread().isInterrupted());
			}

			private void fireStatusUpdate(final String propertyName, final Object oldValue, final Object newValue)
					throws InterruptedException, InvocationTargetException {
				SwingUtilities.invokeAndWait(new Runnable() {
					public void run() {
						firePropertyChange(propertyName, oldValue, newValue);
					}
				});
			}

			private void fireProgressUpdate(final int progress) throws InterruptedException, InvocationTargetException {
				SwingUtilities.invokeAndWait(new Runnable() {
					public void run() {
						setProgress(progress);
					}
				});
			}
			

			private void saveMap(MapModel map) throws InterruptedException, InvocationTargetException {
				if (!this.mapHasChanged) {
					return;
				}
				fireStatusUpdate(SwingWorkerDialog.DETAILS_LOG_TEXT, null, TextUtils.getText("update_references_save_map")
						+ map.getURL().getPath());

				map.setSaved(false);
				((MFileManager) UrlManager.getController()).save(map, false);
			}
		
		};
	}
	
}
