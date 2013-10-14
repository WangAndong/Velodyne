package velodyne2d;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.media.opengl.GL2;


import detection.Track;
import detection.TrackManager;
import detection.VehicleModel;

import VelodyneDataIO.LidarFrame;
import VelodyneView.AnimatorStopper;
import VelodyneView.LidarFrameProcessor;

public class LidarGLViewerForTracking extends LidarGLViewerForDetection{
	
	static Point2D origin = new Point2D(0,0);
	
	private boolean showPrior;//show particles/vehicles before resampling
	private boolean showCar;//show vehicles or particles
	private boolean slowMotion;
	
	private Collection<Track> tracks;//existed tracks
	private List<VehicleModel> newVehicles;//new detected vehicles
	
	private TrackManager manager;
	
	double xmax;
	double xmin;
	double ymax;
	double ymin;
	
	double ego_xmax;
	double ego_xmin;
	double ego_ymax;
	double ego_ymin;
	
	String trackLogFile;
	
	public LidarGLViewerForTracking(LidarFrameProcessor processor, Properties conf) {
		super(processor);
		showPrior = false;
		showCar = false;
		slowMotion = false;
		tracks = null;
		newVehicles = new ArrayList<VehicleModel>();
		
		try{
			manager = new TrackManager(conf);
		}catch(Exception e){
			e.printStackTrace();
			throw new RuntimeException();
		}
		//load range for 2d range filter, useful in freeway dataset
		xmax = Double.parseDouble(conf.getProperty("xmax"));
		xmin = Double.parseDouble(conf.getProperty("xmin"));
		ymax = Double.parseDouble(conf.getProperty("ymax"));
		ymin = Double.parseDouble(conf.getProperty("ymin"));
		
		//load range for 2d range filter, useful in freeway dataset
		ego_xmax = Double.parseDouble(conf.getProperty("ego_xmax"));
		ego_xmin = Double.parseDouble(conf.getProperty("ego_xmin"));
		ego_ymax = Double.parseDouble(conf.getProperty("ego_ymax"));
		ego_ymin = Double.parseDouble(conf.getProperty("ego_ymin"));
		
		if(Double.isNaN(xmax)){
			xmax = LidarFrame.MAX_RANGE;
		}
		if(Double.isNaN(xmin)){
			xmin = -LidarFrame.MAX_RANGE;
		}
		if(Double.isNaN(ymax)){
			ymax = LidarFrame.MAX_RANGE;
		}
		if(Double.isNaN(ymin)){
			ymin = -LidarFrame.MAX_RANGE;
		}
		
	}
	
	@Override
	protected void renderLidarScene(GL2 gl) {
		this.localWorldFrame = this.lidarFrameProcessor.getScan().getLocalWorldFrame();
		this.lidarFrameProcessor.rangeFilter(xmin, xmax, ymin, ymax);
		this.renderLidarFrame(gl, this.lidarFrameProcessor.getScan().getPoints2D(null), new float[] {0,0,1});
//		this.renderLidarFrame(gl, this.lidarFrameProcessor.getScan().getPoints2D(this.lidarFrameProcessor.getRangeMask()), new float[] {0,1,0});
		if(!framePause){//update tracks and detections, pause when new track us added
			//update tracks
			int numOfTrack  = manager.getNumOfTrack();
			manager.update(this.lidarFrameProcessor.getScan(), mTrans, this.lidarFrameProcessor.timestamp, this.lidarFrameProcessor.getRangeMask());
			int diff = manager.getNumOfTrack() - numOfTrack;
			if(diff<0){
				System.out.printf("-------------------------------------------------------%d track is deleted\n", diff);
			}
			//detection
			List<Segment> segments1 = this.lidarFrameProcessor.findMotionSegment();
			List<Segment> segments2 = this.lidarFrameProcessor.extractLines(segments1);
			for(Segment seg: segments2){
				if(seg.getMotion().isMoving()==0) continue;
				if(seg.getCenter().distance(origin)>30) continue;
				Point2D segCenterInBodyFrame = mTrans.transform(localWorldFrame, this.lidarFrameProcessor.getScan().bodyFrame, seg.getCenter());
				if(segCenterInBodyFrame.x<ego_xmax && segCenterInBodyFrame.x>ego_xmin 
						&& segCenterInBodyFrame.y<ego_ymax && segCenterInBodyFrame.y>ego_ymin) continue;
				
				VehicleModel v = this.lidarFrameProcessor.getVehInitializer().initialize(seg);
				if(v==null){
					continue;
				}else if(manager.isTracked(v.center, this.localWorldFrame, mTrans)){
					continue;
				}
				newVehicles.add(v);
			}
			//add new vehicles to track manager
			manager.add(newVehicles, this.lidarFrameProcessor.timestamp);
			if(newVehicles.size()>0 || slowMotion){
				if(newVehicles.size()>0){
					System.out.printf("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++new track is added\n");
				}
				System.out.printf("totally %d tracks\n", manager.getNumOfTrack());
//				framePause=true;
//				slowMotion=true;
				newVehicles.clear();
			}
			tracks = manager.getTracks();
			//visualize tracks
			for(Track track : tracks){
				//visualize average vehicle state
				this.renderVehicle(gl, track.getAvgVehicle(), new float[] {1, 0, 0});
				//particles
				Point2D[] particles = mTrans.transform(null, this.localWorldFrame, track.getPosterior());
				this.renderPoints(gl, particles, new float[] {1, 1, 1}, 1);
			}
			//visualize track trajectories
			Set<Integer> ids = manager.getIds();
			for(Integer id: ids){
				ArrayList<Point2D> traj = manager.getTraj(id);
				this.renderTraj(gl, mTrans.transform(null, localWorldFrame, traj), new float[] {0, 1, 0});
			}
			
		}
		else{//draw the current tracks for debugging
			//visualize track
			for(Track t : tracks){
				//visualize average vehicle state
				this.renderVehicle(gl, t.getAvgVehicle(), new float[] {1, 0, 0});
				//visualize the particles before/after resampling
				if(showPrior){
					Point2D[] particles = mTrans.transform(null, this.localWorldFrame, t.getPrior(1));
					this.renderPoints(gl, particles, new float[] {1, 1, 1}, 1);
					particles = mTrans.transform(null, this.localWorldFrame, t.getPrior(0.1));
					this.renderPoints(gl, particles, new float[] {1, 0, 0}, 2);
				}else{
					Point2D[] particles = mTrans.transform(null, this.localWorldFrame, t.getPosterior());
					this.renderPoints(gl, particles, new float[] {1, 1, 1}, 1);
				}
				//visualize cars
				if(showCar){
					VehicleModel[] vehs = null;
					if(showPrior){
						vehs = t.getPriorVehicle(0.1); 
					}else{
						vehs = t.getPosteriorVehicle();
					}
					for(VehicleModel veh: vehs){
						float a=1;
						this.renderVehicle(gl, veh, new float[]{a, a, a});
					}
				}
			}
		}
	}

	
	private void logAllTracks(){
		this.manager.logAllTracks();
	}
	
	@Override
	public void keyPressed(KeyEvent e) {
		// TODO Auto-generated method stub
		int key = e.getKeyCode();
		switch(key){
		case KeyEvent.VK_ESCAPE:
			System.out.println("escape is pressed");
			this.logAllTracks();
			new AnimatorStopper(this.getAnimator()).start();
			break;
		case KeyEvent.VK_LEFT:
			if(this.orthoview){
				this.orthoCam.y+=10;
				this.orthoCam.lookat_y+=10;
			}else{
				this.cam.rotAngle+=10;this.cam.update();
			}
			break;
		case KeyEvent.VK_RIGHT:
			if(this.orthoview){
				this.orthoCam.y-=10;
				this.orthoCam.lookat_y-=10;
			}else{
				this.cam.rotAngle-=10;this.cam.update();	
			}
			break;
		case KeyEvent.VK_UP:
			if(this.orthoview){
				this.orthoCam.x-=10;
				this.orthoCam.lookat_x-=9;
			}else{
				this.cam.vertAngle-=10;this.cam.update();	
			}
			break;
		case KeyEvent.VK_DOWN:
			if(this.orthoview){
				this.orthoCam.x+=10;
				this.orthoCam.lookat_x+=9;
			}else{
				this.cam.vertAngle+=10;this.cam.update();	
			}
			break;
		case KeyEvent.VK_O:
			if(this.orthoview){
				this.orthoCam.z += 1;	
			}else{
				this.cam.dist += 1;this.cam.update();
			}
			break;
		case KeyEvent.VK_I:
			if(this.orthoview){
				this.orthoCam.z -= 1;
			}else{
				this.cam.dist -= 1;this.cam.update();
			}
			break;
		case KeyEvent.VK_SPACE:
			this.framePause=!this.framePause;break;
		case KeyEvent.VK_P:
			this.showPrior=!this.showPrior;break;
		case KeyEvent.VK_C:
			this.showCar=!this.showCar;break;
		case KeyEvent.VK_S:
			this.slowMotion=!this.slowMotion;
			this.framePause=!this.framePause;
			break;
		case KeyEvent.VK_V:
			this.orthoview = !this.orthoview; break;
		}
	}
	
}
