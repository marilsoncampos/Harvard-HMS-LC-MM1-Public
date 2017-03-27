/*
 * Lung Cancer Detector Visualizer and Offline Tester
 * by walrus71
 * 
 * Version history:
 * ================
 * 1.0 (2017.02.07)
 * 		- Version at the contest launch
 * 		- Scale up scoring
 * 		- Small fixes
 * 0.7 (2017.02.02)
 * 		- Faster drawing based on Jaco Cronje's code
 * 0.6 (2017.02.01)
 * 		- Works without contours
 *      - Small UI improvements
 * 0.5 (2017.01.30)
 * 		- Updates for new scoring method
 * 0.4 (2017.01.26)
 * 		- Updates for data format v4
 *  	- Small UI improvements
 * 0.3 (2017.01.24)
 * 		- Updates for data format v3
 * 0.2 (2017.01.19)
 *      - F-score based scoring
 *      - Small UI improvements
 * 0.1 (2017.01.17)
 *      - Initial version
 */
package visualizer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.imageio.ImageIO;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class Visualizer implements ActionListener, ItemListener, MouseListener, ChangeListener {
	
	private static final String CONTOUR_TRUTH = "radiomics_gtv";
	private static final String CONTOUR_SOLUTION = "solution";
	private static final String SHOW_STRUCTURE_TEXT = "Show ";
	
	private static Set<String> CONTOUR_TRUTH_ALIASES;
	static {
		String[] aliases = {
			"radiomics_gtv", "Radiomics_gtv", "radiomics_gtv2", "radiomics_gtv_nw", "radiomics_gtvr"
		};
		CONTOUR_TRUTH_ALIASES = new HashSet<>();
		for (String s: aliases) CONTOUR_TRUTH_ALIASES.add(s);
	}
	
	private boolean hasGui = true;
	private String[] scanIds;
	private Scan currentScan;
	private int currentSlice = 0;
	private int currentX = 0;
	private int currentY = 0;
	private int dataW, dataH, dataN; // x,y,z sizes of current data matrix
	private double zScale; // ratio of slice thickness to pixel size
	private int[][][] data;	
	private String dataDir;
	private String solutionPath;
	private boolean writeGroundTruth = false; // TODO false 
	private Map<String, Scan> idToScan;
	private int loGray = 1;
	private int hiGray = 2000;
	
	private JFrame frame;
	private JPanel viewPanel, controlsPanel;
	private JCheckBox showTruthCb, showSolutionCb;
	private JSlider loSlider, hiSlider;
	private JLabel grayLevelsLabel;
	private JComboBox<String> structureSelectorComboBox, scanSelectorComboBox, zoomSelectorComboBox;
	private JTextArea logArea;
	private MapView mapView;
	private Font font = new Font("SansSerif", Font.PLAIN, 20);
	private int[] zoomLevels = new int[] {2,3,4,6,8};
	private int zoom = 2;
	
	private Color solutionBorderColor	= new Color(255, 255,   0, 255);
	private Color solutionFillColor     = new Color(255, 255,   0,  50);
	private Color solutionMarkerColor   = new Color(255, 255,   0, 200);
	private Color truthBorderColor      = new Color(  0, 255, 255, 255);
	private Color truthFillColor        = new Color(  0, 155, 255,  50);
	private Color truthMarkerColor      = new Color(  0, 155, 255, 200);
	private Color extraBorderColor      = new Color(255,   0, 255, 100);
	private Color extraFillColor        = new Color(255,   0, 255,  50);

	private boolean firstImage = true;
	
	private void run() {
		sbBoxes.append("[");
		loadMetaData();
		if (solutionPath != null) {
			loadSolution();
		} else {
			loadSolutionPixels();
		}
		
		if (hasGui) {
			DefaultComboBoxModel<String> cbm = new DefaultComboBoxModel<>(scanIds);
			scanSelectorComboBox.setModel(cbm);
			scanSelectorComboBox.setSelectedIndex(0);
			scanSelectorComboBox.addItemListener(this);
		}
		
		String detailsMarker = "Details:";
		log(detailsMarker);
		log("    #img: \tTP\tFP\tFN\tF-score");
		double totalSum = 0;
		int cnt = 0;
		for (String id: scanIds) {
			// System.out.println("scanId " + id); eg. ANON_LUNG_TC557
			log(id);
			Metric[] result = score(id);
			if (result != null) {
				double tp = 0, fp = 0, fn = 0;
				for (int i = 0; i < result.length; i++) {
					Metric m = result[i];
					if (m != null) {
						tp += m.tp;
						fp += m.fp;
						fn += m.fn;
					}
				}
				double score = 0;
				if (tp > 0) {
					double t = tp + fn;
					double fn2 = fn * t / tp;
					double e = fn2 + fp;
					double a = Math.pow(36 * Math.PI * t * t, (double)1 / 3);
					double exp1 = e / t;
					double exp2 = e / (10 * a);
					score = Math.exp(-(exp1 + exp2)/2);
				}
				log("  tp    : " + f(tp));
				log("  fp    : " + f(fp));
				log("  fn    : " + f(fn));
				log("  score : " + f6(score));
			
				totalSum += score;
				cnt++;
				for (int i = 0; i < result.length; i++) {
					Metric m = result[i];
					if (m != null) {
						int i1 = i + 1;
						double f = 0;
						if (m.tp > 0) {
							double prec = m.tp / (m.tp + m.fp);
							double rec  = m.tp / (m.tp + m.fn);
							f = 2 * prec * rec / (prec + rec);
						}
						log("    #" + i1 + ": \t" + (int)(m.tp) + "\t" + (int)(m.fp) + "\t" + 
								(int)(m.fn) + "\t" + f(f));
					}
				}
			}
		}
		sbBoxes.append("]");
		
		if (cnt > 0) {
			double score = 1000000 * totalSum / cnt;
			String result = "Overall score: " + f6(score);
			if (hasGui) { // display final result at the top
				String allText = logArea.getText();
				int pos = allText.indexOf(detailsMarker);
				String s1 = allText.substring(0, pos);
				String s2 = allText.substring(pos);
				allText = s1 + result + "\n\n" + s2;
				logArea.setText(allText);
				logArea.setCaretPosition(0);
				//System.out.println(result);
			}
			else {
				log(result);
			}
		}
		else {
			log("Can't score.");
		}
		
		if (writeGroundTruth) {
			writeGroundTruth();
		}
		
		// the rest is for UI, not needed for scoring
		if (!hasGui) return;
		
		currentScan = idToScan.get(scanIds[0]);
		loadDetails(0);
		repaintMap();
	}

	/*
	 * This is a private helper method to create a version of the data used
	 * in online scoring. Should be ignored by contestants.
	 */
	private void writeGroundTruth() {
		try {
			FileOutputStream out = new FileOutputStream("truth.csv");
			for (String id: scanIds) {
				Scan scan = idToScan.get(id);
				StringBuilder sb = new StringBuilder();
				sb.append(id).append(",slice-dz");
				for (int i = 0; i < scan.slices.size(); i++) {
					Slice s = scan.slices.get(i);
					sb.append(",").append(f(s.dz));
				}
				sb.append("\n");
				out.write(sb.toString().getBytes());
				for (int i = 0; i < scan.slices.size(); i++) {
					Slice s = scan.slices.get(i);
					List<Polygon> pList = s.nameToPolygons.get(CONTOUR_TRUTH);
					if (pList == null) continue;
					for (Polygon p: pList) {
						sb = new StringBuilder();
						sb.append(id).append(",").append(i+1);
						for (P2 p2: p.points) {
							// Convert back to physical space 
							P2 pMm = pixelToMm(p2, s);
							sb.append(",").append(f(pMm.x)).append(",").append(f(pMm.y));
						}
						sb.append("\n");
						out.write(sb.toString().getBytes());
					}
				}
			}
			out.close();
		} 
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Metric[] score(String id) {
		Scan scan = idToScan.get(id);
		Metric[] ret = new Metric[scan.slices.size()];
		for (int i = 0; i < scan.slices.size(); i++) {
			Slice slice = scan.slices.get(i);
			File scanDir = new File(dataDir, scan.id);
			File dir = new File(scanDir, "pngs");
			Slice slice0 = scan.slices.get(i);
			File f = null;
			try {
				int k1 = i + 1;
				f = new File(dir, k1 + ".png");
				if (!f.exists()) {
					System.out.println("Can't find image file: " + f.getAbsolutePath());
					continue;
				}
			} catch (Exception e) {
				log("Can't find image file: " + dir.getAbsolutePath());
			}
			double areaTruth = 0;
			List<Polygon> truthPolygons = slice.nameToPolygons.get(CONTOUR_TRUTH);
			if (!firstImage) {
			    sbBoxes.append(",");
			} else {
			    firstImage = false;
			}
			sbBoxes.append("{\"image_path\": \"" + f.getAbsolutePath() + "\",\n");
			sbBoxes.append("\"rects\": [\n");
			boolean firstPoly = true;
			if (truthPolygons != null && truthPolygons.size() > 0) {	
			        for (Polygon p: truthPolygons) {
					areaTruth += p.area;
					Rectangle2D rect = p.getShape().getBounds2D();
					if (!firstPoly) {
						sbBoxes.append(",");
					} else {
						firstPoly = false;
					}
					sbBoxes.append("{"
							+ "\"x1\": " + rect.getMinX() + ","
							+ "\"x2\": " + rect.getMaxX() + ","
							+ "\"y1\": " + rect.getMinY() + ","
							+ "\"y2\": " + rect.getMaxY()
					    + "}\n");
				}
			}
			sbBoxes.append("]}\n");
			double areaSolution = 0;
			List<Polygon> solutionPolygons = slice.nameToPolygons.get(CONTOUR_SOLUTION);
			if (solutionPolygons != null) {
				for (Polygon p: solutionPolygons) areaSolution += p.area;
			}
			Metric m = new Metric();
			if (areaTruth == 0) { 
				if (areaSolution == 0) { // neither exist
					ret[i] = null;
					continue;
				}
				else { // no truth, false sol
					m.fp = areaSolution;
				}
			}
			else {
				if (areaSolution == 0) { // truth, no sol
					m.fn = areaTruth;
				}
				else { // both exist, calc tp,fp,fn
					Area shapeT = new Area();
					for (Polygon p: truthPolygons) shapeT.add(p.shape);
					Area shapeS = new Area();
					for (Polygon p: solutionPolygons) shapeS.add(p.shape);
					shapeT.intersect(shapeS);
					double overlap = Math.abs(area(shapeT));
					m.tp = overlap;
					m.fp = areaSolution - overlap;
					m.fn = areaTruth - overlap;
				}
			}
			// multiply areas with voxel volume
			double v = slice.dx * slice.dy * slice.dz;
			m.tp *= v; m.fp *= v; m.fn *= v;
			ret[i] = m;
		}
		return ret;
	}
	
	private class Metric {
		public double tp;
		public double fp;
		public double fn;
	}
	
	// based on http://stackoverflow.com/questions/2263272/how-to-calculate-the-area-of-a-java-awt-geom-area
	private double area(Area shape) {
		PathIterator i = shape.getPathIterator(null);
		double a = 0.0;
        double[] coords = new double[6];
        double startX = Double.NaN, startY = Double.NaN;
        Line2D segment = new Line2D.Double(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        while (! i.isDone()) {
            int segType = i.currentSegment(coords);
            double x = coords[0], y = coords[1];
            switch (segType) {
            case PathIterator.SEG_CLOSE:
                segment.setLine(segment.getX2(), segment.getY2(), startX, startY);
                a += area(segment);
                startX = startY = Double.NaN;
                segment.setLine(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
                break;
            case PathIterator.SEG_LINETO:
                segment.setLine(segment.getX2(), segment.getY2(), x, y);
                a += area(segment);
                break;
            case PathIterator.SEG_MOVETO:
                startX = x;
                startY = y;
                segment.setLine(Double.NaN, Double.NaN, x, y);
                break;
            }
            i.next();
        }
        if (Double.isNaN(a)) {
            throw new IllegalArgumentException("PathIterator contains an open path");
        } 
        else {
            return 0.5 * Math.abs(a);
        }
    }

    private double area(Line2D seg) {
        return seg.getX1() * seg.getY2() - seg.getX2() * seg.getY1();
    }
    
    private void loadMetaData() {
    	log("Loading scan list from " + dataDir + " ...");
		// gather scan ids
    	idToScan = new HashMap<>();
    	for (File f: new File(dataDir).listFiles()) {
    		if (f.isDirectory() && new File(f, "auxiliary").exists()) {
    			String id = f.getName();
    			Scan scan = new Scan(id);
    			idToScan.put(id, scan);
    		}
    	}
    	scanIds = idToScan.keySet().toArray(new String[0]);
		Arrays.sort(scanIds);
		
		int scanCnt = scanIds.length;
		int progressN =  Math.max(1, scanCnt / 20);		
		
		String line = null;
		int lineNo = 0;
		
		int cnt = 0;
		paintProgress(0);
    	// load scan and slice meta data
		for (Scan scan: idToScan.values()) {
			File scanDir = new File(dataDir, scan.id);
			
			// load structure names and ids
	    	try {
				File f = new File(scanDir, "structures.dat");
				if (f.exists()) {
					LineNumberReader lnr = new LineNumberReader(new FileReader(f));
					while (true) {
						line = lnr.readLine();
						lineNo++;
						if (line == null) break;
						line = line.trim();
						if (line.isEmpty()) continue;
						// body|Esophagus|lung,radiomics_gtv 
				    	String[] parts = line.split("\\|");
				    	for (int i = 0; i < parts.length; i++) {
				    		String contourName = parts[i];
				    		if (CONTOUR_TRUTH_ALIASES.contains(contourName)) {
				    			contourName = CONTOUR_TRUTH;
				    		}
				    		// structure ids are 1-based
				    		scan.structureIdToName.put(i+1, contourName);
				    	}
					}
					lnr.close();
				}
			} 
			catch (Exception e) {
				log("Error reading structures.dat file for scan " + scan.id);
				log("Line #" + lineNo + ": " + line);
				e.printStackTrace();
				System.exit(0);
			}
			
			File dir = new File(scanDir, "auxiliary");
			for (File f: dir.listFiles()) {
				// name is like 103.dat
				String[] nameParts = f.getName().split("\\.");
				int sliceOrdinal = Integer.parseInt(nameParts[0]);
				if (scan.slices.size() < sliceOrdinal) {
					int missing = sliceOrdinal - scan.slices.size();
					for (int i = 0; i < missing; i++) {
						scan.slices.add(new Slice());
					}
				}
				Slice slice = scan.slices.get(sliceOrdinal - 1);
				slice.absolutePath = f.getAbsolutePath();
		    	line = null;
				lineNo = 0;
				try {
					LineNumberReader lnr = new LineNumberReader(new FileReader(f));
					while (true) {
						line = lnr.readLine();
						lineNo++;
						if (line == null) break;
						line = line.trim();
						/*
						(0020.0032),-250,-250,-47.5
						(0020.0037),1,0,0,0,1,0
						(0018.0050),2.5
						(0028.0010),512
						(0028.0011),512
						(0028.0030),9.76562e-1,9.76562e-1
						(0028.1052),-1024
						(0028.1053),1
						(0028.1054),HU
						*/
						String[] parts = line.split(",");
						String tag = parts[0];
						if (tag.equals("(0020.0032)")) {
							slice.x0 = Double.parseDouble(parts[1]);
							slice.y0 = Double.parseDouble(parts[2]);
							slice.z  = Double.parseDouble(parts[3]);
						}
						else if (tag.equals("(0028.0010)")) {
							slice.w = Integer.parseInt(parts[1]);
						}
						else if (tag.equals("(0028.0011)")) { 
							slice.h = Integer.parseInt(parts[1]);
						}
						else if (tag.equals("(0028.0030)")) {
							slice.dx = Double.parseDouble(parts[1]);
							slice.dy = Double.parseDouble(parts[2]);
						}
						else if (tag.equals("(0018.0050)")) {
							slice.dz = Double.parseDouble(parts[1]);
						}
					}
					lnr.close();
				} 
				catch (Exception e) {
					log("Error reading auxiliary file for : " + scan.id);
					log("Line #" + lineNo + ": " + line);
					e.printStackTrace();
					System.exit(0);
				}
			} // for .aux files
		
			// load contours
			dir = new File(scanDir, "contours");
			loadContours(scan, dir, true); // only TRUTH now
			
			if (cnt % progressN == 0) {
		    	paintProgress((double)cnt / scanCnt);
		    }
			cnt++;			
		} // for scans
	}
    
    private void loadSolution() {
		File f = new File(solutionPath);
		if (f.exists()) {
			log("Loading solution file from " + solutionPath);
			String line = null;
			int lineNo = 0;
			try {
				LineNumberReader lnr = new LineNumberReader(new FileReader(f));
				while (true) {
					line = lnr.readLine();
					lineNo++;
					if (line == null) break;
					line = line.trim();
					if (line.isEmpty()) continue;
					// ANON_LUNG_TC001,100,x1,y1,x2,y2,...
					String[] parts = line.split(",");
					String id = parts[0];
					Scan scan = idToScan.get(id);
					if (scan == null) {
						log("Unknown scan id found in solution file at line " + lineNo + ": " + id);
						System.exit(0);
					}
					
					int sliceOrdinal = Integer.parseInt(parts[1]);
					if (scan.slices.size() < sliceOrdinal) {
						log("Unknown slice id found in solution file at line " + lineNo + ": " + id + ", " + sliceOrdinal);
						System.exit(0);
					}
					Slice slice = scan.slices.get(sliceOrdinal - 1);
					
					String contourName = CONTOUR_SOLUTION;
					List<Polygon> polygons = slice.nameToPolygons.get(contourName);
					if (polygons == null) {
						polygons = new Vector<>();
						slice.nameToPolygons.put(contourName, polygons);
					}
					
					int skip = parts[0].length() + parts[1].length() + 2;
					line = line.substring(skip);
					P2[] points = coordStringToPoints(line, 2); // nCoords is 2: x,y
					// convert to pixels
					int n = points.length;
					for (int i = 0; i < n; i++) {
						points[i] = mmToPixel(points[i], slice);
					}
			    	Polygon p = new Polygon(points); 
					polygons.add(p);
				}
				lnr.close();
			} 
			catch (Exception e) {
				log("Error reading solution file");
				log("Line #" + lineNo + ": " + line);
				e.printStackTrace();
				System.exit(0);
			}
		}
		else {
			log("Can't find solution file " + f.getAbsolutePath());
		}
	}

	private void loadSolutionPixels() {
		File f = new File("/Users/ateodorescu/ml/tensorbox/provisional_full_raw_resnet.txt");
		//File f = new File("/Users/ateodorescu/ml/tensorbox/provisional_full_raw.txt");
		//File f = new File("/Users/ateodorescu/ml/tensorbox/test_raw.txt");
		//File f = new File("/Users/ateodorescu/ml/tensorbox/training_boxes_full_raw.txt");
		HashMap<String, String> latestPoly = new HashMap<String, String>();
		Set<String> scoreAbove025 = new HashSet<String>();
		Set<String> scoreAbove015 = new HashSet<String>();

		if (f.exists()) {
			log("Loading pixels solution file from " + f.getAbsolutePath());
			for (int step = 0; step < 3; step++) {
				String path = null;
				String rect = null;
				int lineNo = 0;
				// TODO: Copy scoreabove025 if I add multiple steps.
				try {
					LineNumberReader lnr = new LineNumberReader(new FileReader(f));
					while (true) {
						path = lnr.readLine(); // /Users/ateodorescu/ml/tensorbox/src/../provisional_extracted_no_gt/ANON_LUNG_TC003/pngs/4.png
						rect = lnr.readLine();
						if (rect == null || rect.length() == 0 || path == null || path.length() == 0) {
							break;
						}
						String[] rct = rect.split(" ");
						if (rct.length < 5) {
							continue;
						}
						double x1 = (Double.parseDouble(rct[0]));
						double x2 = (Double.parseDouble(rct[1]));
						double y1 = (Double.parseDouble(rct[2]));
						double y2 = (Double.parseDouble(rct[3]));
						double score = Double.parseDouble(rct[4]);
						// TODO(andrei): Figure out how this score depends on the training ckpt.
						String[] pth = path.split("/");
						String id = pth[8];
						if (step == 0) {
							if (score < 0.20) {
								continue;
							}
						} else if (step == 1) {
							if (score < 0.15) {
								continue;
							}
							if (scoreAbove025.contains(id)) {
								continue;
							}
						} else if (step == 2) {
							if (score < 0.10) {
								continue;
							}
							if (scoreAbove015.contains(id) || scoreAbove025.contains(id)) {
								continue;
							}
						}
						lineNo++;
						Scan scan = idToScan.get(id);
						if (scan == null) {
							log("Unknown scan id found in solution file at line " + lineNo + ": " + id);
							System.exit(0);
						}
						int sliceOrdinal = Integer.parseInt(pth[10].split("\\.")[0]);
						if (scan.slices.size() < sliceOrdinal) {
							log("Unknown slice id found in solution file at line " + lineNo + ": " + id + ", " + sliceOrdinal);
							System.exit(0);
						}
						Slice slice = scan.slices.get(sliceOrdinal - 1);

						String contourName = CONTOUR_SOLUTION;
						List<Polygon> polygons = slice.nameToPolygons.get(contourName);
						if (polygons == null) {
							polygons = new Vector<>();
							slice.nameToPolygons.put(contourName, polygons);
						}
						if (x1 > x2) {
							double aux = x1;
							x1 = x2;
							x2 = aux;
						}
						if (y1 > y2) {
							double aux = y1;
							y1 = y2;
							y2 = aux;
						}
						int np = 8;
						P2[] points = new P2[np];
					points[0] = pixelToMm(new P2(x1, y1), slice);
					points[1] = pixelToMm(new P2(x2, y1), slice);
					points[2] = pixelToMm(new P2(x2, y2), slice);
					points[3] = pixelToMm(new P2(x1, y2), slice);
					x1 = points[0].x;
					x2 = points[1].x;
					y1 = points[0].y;
					y2 = points[3].y;
						if (polygons != null && polygons.size() > 0) {
							Polygon p = polygons.get(0);
							x1 = Math.min(x1, p.minx);
							x2 = Math.max(x2, p.maxx);
							y1 = Math.min(y1, p.miny);
							y2 = Math.max(y2, p.maxy);
						}
						points[0] = new P2(x1, y1);
						points[1] = new P2(x2, y1);
						points[2] = new P2(x2, y2);
						points[3] = new P2(x1, y2);
						// Makes a circle instead of a square

						points[0] = new P2((x1 + x2) / 2, y1);
						points[1] = new P2((1 * x1 + 7 * x2) / 8, (7 * y1 + 1 * y2) / 8);
						points[2] = new P2(x2, (y1 + y2) / 2);
						points[3] = new P2((x1 + 7 * x2) / 8, (y1 + 7 * y2) / 8);
						points[4] = new P2((x1 + x2) / 2, y2);
						points[5] = new P2((7 * x1 + x2) / 8, (y1 + 7 * y2) / 8);
						points[6] = new P2(x1, (y1 + y2) / 2);
						points[7] = new P2((7 * x1 + x2) / 8, (7 * y1 + y2) / 8);
						String sol = id + "," + sliceOrdinal;
						for (int i = 0; i < np; i++)
							sol += "," + (int) points[i].x + "," + (int) points[i].y;
						latestPoly.put(id + "," + sliceOrdinal, sol);
						if (step == 0) {
							scoreAbove025.add(id);
						} else if (step == 1) {
							scoreAbove015.add(id);
						}
						Polygon p = new Polygon(points);
						if (polygons.isEmpty()) {
							polygons.add(p);
						} else {
							polygons.clear();
							polygons.add(p);
						}
					}
					//lnr.close();
				} catch (Exception e) {
					log("Error reading solution file");
					log("Line #" + lineNo + ": " + path + " " + rect);
					e.printStackTrace();
					System.exit(0);
				}
			}
		}
		else {
			log("Can't find solution file " + f.getAbsolutePath());
		}
		for (String row : latestPoly.values()) {
			System.out.println(row);
		}
	}
    
    private void loadContours(Scan scan, File dir, boolean truth) {
    	if (dir == null || !dir.exists() || !dir.isDirectory()) return;
    	for (File f: dir.listFiles()) {
			// name is like 100.1.dat
			String[] nameParts = f.getName().split("\\.");
			int sliceOrdinal = Integer.parseInt(nameParts[0]);
			if (scan.slices.size() < sliceOrdinal) {
				log("Slice " + sliceOrdinal + " doesn't exist for scan: " + scan.id);
				System.exit(0);
			}
			Slice slice = scan.slices.get(sliceOrdinal - 1);
			
			int contourId = Integer.parseInt(nameParts[1]);
			String contourName = scan.structureIdToName.get(contourId);
			if (contourName == null) {
				log("Contour Id " + contourId + " not known for scan: " + scan.id);
				System.exit(0);
			}
			
			if (truth && !contourName.equals(CONTOUR_TRUTH)) continue;
			if (!truth && contourName.equals(CONTOUR_TRUTH)) continue;
			
			scan.structureNames.add(contourName);
			
			List<Polygon> polygons = slice.nameToPolygons.get(contourName);
			if (polygons == null) {
				polygons = new Vector<>();
				slice.nameToPolygons.put(contourName, polygons);
			}
			
	    	String line = null;
			int lineNo = 0;
			try {
				LineNumberReader lnr = new LineNumberReader(new FileReader(f));
				while (true) {
					line = lnr.readLine();
					lineNo++;
					if (line == null) break;
					line = line.trim();
					P2[] points = coordStringToPoints(line, 3); // nCoords is 3: x,y,z
					int n = points.length;
					// convert to pixels
					for (int i = 0; i < n; i++) {
						points[i] = mmToPixel(points[i], slice);
					}
			    	Polygon p = new Polygon(points);
					polygons.add(p);
				}
				lnr.close();
			} 
			catch (Exception e) {
				log("Error reading contour file " + f.getName() + " for scan: " + scan.id);
				log("Line #" + lineNo + ": " + line);
				e.printStackTrace();
				System.exit(0);
			}
		}
    }
	
	private void loadDetails(int sliceToLoad) { // images and extra structures
		paintProgress(0);
		File scanDir = new File(dataDir, currentScan.id);
		File dir = new File(scanDir, "pngs");  
		Slice slice0 = currentScan.slices.get(0);
		dataW = slice0.w; // assuming all images of a scan have same size
		dataH = slice0.h;
		dataN = currentScan.slices.size();
		data = new int[dataW][dataH][dataN];
		double dz = Math.abs(slice0.z - currentScan.slices.get(dataN-1).z);
		double dx = slice0.dx * slice0.w;
		zScale = dz / dx;
		int n = currentScan.slices.size();
		int progressN = n / 20;
		for (int k = 0; k < n; k++) {
			File f = null;
			try {
				int k1 = k+1;
				f = new File(dir, k1 + ".png");
				if (!f.exists()) {
					log("Can't find image file: " + f.getAbsolutePath());
					return;
				}
				BufferedImage img2 = ImageIO.read(f);
			    Raster r2 = img2.getRaster();
			    DataBuffer db2 = r2.getDataBuffer();
			    for (int i = 0; i < dataW; i++) for (int j = 0; j < dataH; j++) {
			    	int c = db2.getElem(dataW * j + i);
				    if (c < 0) c += 65536; // stored as short
				    data[i][j][k] = c;
			    }
			    if (k % progressN == 0) {
			    	paintProgress((double)k / n);
			    }
			}
			catch (Exception e) {
				log("Error reading image " + f.getAbsolutePath());
				e.printStackTrace();
			}
		}
		currentSlice = sliceToLoad;
		currentY = dataH / 2;
		currentX = dataW / 2;
		
		// load extra contours if not done yet
		if (currentScan.structureIdToName.size() > currentScan.structureNames.size()) {
			dir = new File(scanDir, "contours");
			loadContours(currentScan, dir, false); // anything but TRUTH
		}
		
		// populate extra struct combobox
		Set<String> keys = new HashSet<>();
		keys.add("Don't show extra structures");
		for (String key: currentScan.structureNames) {
			if (key.equals(CONTOUR_TRUTH)) continue;
			keys.add(SHOW_STRUCTURE_TEXT + key);
		}
		String[] arr = keys.toArray(new String[0]);
		Arrays.sort(arr);
		DefaultComboBoxModel<String> cbm = new DefaultComboBoxModel<>(arr);
		structureSelectorComboBox.setModel(cbm);
		structureSelectorComboBox.setSelectedIndex(0);
				
		paintProgress(-1);
		if (mapView != null) mapView.clearMetrics();		
	}

	private class P2 {
		public double x;
		public double y;

		public P2(double x, double y) {
			this.x = x; this.y = y;
		}
		
		@Override
		public String toString() {
			return f(x) + ", " + f(y);
		}
		
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof P2)) return false;
			P2 p = (P2)o;
			double d2 = (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y);
			return d2 < 1e-6;
		}
	}
	
	private class Slice {
		public int w, h; // image size
		public double x0, y0, z; // real space (mm)
		public double dx, dy, dz; // pixel size (mm / pixel)
		public Map<String, List<Polygon>> nameToPolygons = new HashMap<>();
		public String absolutePath;
	}
	
	private class Scan {
		public String id;
		public List<Slice> slices = new Vector<>();
		public Set<String> structureNames = new HashSet<>();
		public Map<Integer, String> structureIdToName = new HashMap<>();
		
		public Scan(String id) {
			this.id = id;
		}
	}
	
	// Convert from image space (pixels) to physical space (millimeters)
	private P2 pixelToMm(P2 p, Slice slice) {
		double x = (p.x * slice.dx) + slice.x0;
		double y = (p.y * slice.dy) + slice.y0;
		return new P2(x, y);
	}
	
	// Convert from physical space (millimeters) to image space (pixels)
	private P2 mmToPixel(P2 p, Slice slice) {
		double x = (p.x - slice.x0) / slice.dx;
		double y = (p.y - slice.y0) / slice.dy;
		return new P2(x, y);
	}
	
	private P2[] coordStringToPoints(String coordString, int nCoords) {
		// x1,y1,z1,x2,y2,z2,... or x1,y1,x2,y2,... depending on nCoords
		String[] parts = coordString.split(",");
		int n = parts.length / nCoords; 
		P2[] points = new P2[n];
		for (int i = 0; i < n; i++) {
			double x = Double.parseDouble(parts[nCoords*i]);
			double y = Double.parseDouble(parts[nCoords*i+1]);
			points[i] = new P2(x, y);
		}
		return points;		
	}

	private class Polygon {
		public double minx, miny, maxx, maxy;
		public double area = 0;
		private Area shape;
		public P2[] points;		
				
		public Polygon(P2[] points) {
			this.points = points;
			makeBounds();
			getShape();
			getArea();
		}
		
		private void makeBounds() {
			minx = Double.MAX_VALUE;
			miny = Double.MAX_VALUE;
			maxx = -Double.MAX_VALUE;
			maxy = -Double.MAX_VALUE;
			for (P2 p: points) {
				minx = Math.min(p.x, minx);
				maxx = Math.max(p.x, maxx);
				miny = Math.min(p.y, miny);
				maxy = Math.max(p.y, maxy);
			}
		}
		
		public Area getShape() {
			if (shape == null) {
				Path2D path = new Path2D.Double();
				path.setWindingRule(Path2D.WIND_EVEN_ODD);
	
				int n = points.length;
				path.moveTo(points[0].x, points[0].y);
				for(int i = 1; i < n; ++i) {
				   path.lineTo(points[i].x, points[i].y);
				}
				path.closePath();
				shape = new Area(path);
			}
			return shape;
		}
		
		private void getArea() {
			// unsigned area calculated from the points
			double a = 0;
			int n = points.length;
			for (int i = 1; i < n; i++) {
				a += (points[i-1].x + points[i].x) * (points[i-1].y - points[i].y);
			}
			// process last segment if ring is not closed
			if (!points[0].equals(points[n-1])) {
				a += (points[n-1].x + points[0].x) * (points[n-1].y - points[0].y);
			}
			area = Math.abs(a / 2);
		}
		
		@Override
		public String toString() {
			return f(minx) + "," + f(miny) + " - " + 
					f(maxx) + "," + f(maxy);
		}
	}
	
	
	/**************************************************************************************************
	 * 
	 *              THINGS BELOW THIS ARE UI-RELATED, NOT NEEDED FOR SCORING
	 * 
	 **************************************************************************************************/
	
	public void setupGUI() {
		if (!hasGui) return;
		int fullW = (int) Toolkit.getDefaultToolkit().getScreenSize().getWidth();
		frame = new JFrame("Lung Cancer Detector Visualizer");
		int W = 800;
		int H = 900;
		frame.setSize(Math.min(fullW, W * 5 / 3), H);
		frame.setResizable(false);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
		Container cp = frame.getContentPane();
		cp.setLayout(new BorderLayout());
		
		viewPanel = new JPanel();
		viewPanel.setPreferredSize(new Dimension(W, H));
		cp.add(viewPanel, BorderLayout.WEST);
		
		controlsPanel = new JPanel();
		cp.add(controlsPanel, BorderLayout.CENTER);

		viewPanel.setLayout(new BorderLayout());
		mapView = new MapView();
		viewPanel.add(mapView, BorderLayout.CENTER);
		
		controlsPanel.setLayout(new GridBagLayout());
		GridBagConstraints c2 = new GridBagConstraints();
		int y = 0;
		
		showTruthCb = new JCheckBox("Show truth contours");
		showTruthCb.setSelected(true);
		showTruthCb.addActionListener(this);
		c2.fill = GridBagConstraints.BOTH;
		c2.gridx = 0;
		c2.gridy = y++;
		c2.weightx = 1;
		controlsPanel.add(showTruthCb, c2);
		
		showSolutionCb = new JCheckBox("Show solution contours");
		showSolutionCb.setSelected(true);
		showSolutionCb.addActionListener(this);
		c2.gridy = y++;
		controlsPanel.add(showSolutionCb, c2);
		
		structureSelectorComboBox = new JComboBox<>(new String[] {"..."});
		structureSelectorComboBox.addItemListener(this);
		c2.gridy = y++;
		controlsPanel.add(structureSelectorComboBox, c2);
		
		grayLevelsLabel = new JLabel();
		grayLevelsLabel.setBorder(new EmptyBorder(2, 2, 5, 2));
		setGrayLevelText();
		c2.gridy = y++;
		controlsPanel.add(grayLevelsLabel, c2);
		
		Dictionary<Integer, JLabel> dict = new Hashtable<>();
		dict.put(  0, new JLabel("1"));
		dict.put(100, new JLabel("10"));
		dict.put(200, new JLabel("100"));
		dict.put(300, new JLabel("1k"));
		dict.put(400, new JLabel("10k"));
		dict.put(500, new JLabel("100k"));
		loSlider = new JSlider(0, 500);
		loSlider.setPaintTicks(true);
		loSlider.setLabelTable(dict);
		loSlider.setPaintLabels(true);
		loSlider.setValue(grayToSlider(loGray));
		loSlider.addChangeListener(this);
		c2.gridy = y++;
		controlsPanel.add(loSlider, c2);
		
		hiSlider = new JSlider(0, 500);
		hiSlider.setValue(grayToSlider(hiGray));
		hiSlider.addChangeListener(this);
		c2.gridy = y++;
		controlsPanel.add(hiSlider, c2);		
		
		String[] zooms = new String[zoomLevels.length];
		for (int i = 0; i < zoomLevels.length; i++) {
			zooms[i] = zoomLevels[i] + "x zoom";
		}
		zoomSelectorComboBox = new JComboBox<>(zooms);
		zoomSelectorComboBox.setSelectedIndex(0);
		zoomSelectorComboBox.addItemListener(this);
		c2.gridy = y++;
		controlsPanel.add(zoomSelectorComboBox, c2);
		
		scanSelectorComboBox = new JComboBox<>(new String[] {"..."});
		c2.gridy = y++;
		controlsPanel.add(scanSelectorComboBox, c2);
		
		JScrollPane sp = new JScrollPane();
		logArea = new JTextArea("", 10, 20);
		logArea.setFont(new Font("Monospaced", Font.PLAIN, 16));
		logArea.addMouseListener(this);
		sp.getViewport().setView(logArea);
		c2.gridy = y++;
		c2.weighty = 10;
		controlsPanel.add(sp, c2);

		frame.setVisible(true);
	}
	
	private void setGrayLevelText() {
		grayLevelsLabel.setText("Gray levels: " + loGray + " - " + hiGray);
	}
	
	private int sliderToGray(int v) {
		return (int)(Math.pow(10, (double)v / 100));
	}
	private int grayToSlider(int v) {
		if (v == 0) return 0;
		return (int)(100 * Math.log10(v));
	}
	
	private void repaintMap() {
		if (mapView != null) mapView.repaint();
	}
	
	private void paintProgress(double d) {
		if (mapView != null) mapView.paintProgress(d);
	}
	
	@SuppressWarnings("serial")
	private class MapView extends JLabel implements MouseListener, MouseWheelListener, MouseMotionListener {
		
		private int mouseX;
		private int mouseY;
		private double progress = 0;
		private boolean metricsValid = false;
		private int W, H;
		private int smallX0, frontY0, sideY0, smallW, smallH;
		private double smallZScale, smallXScale, smallYScale;
		private int zoomY0, zoomH;
		private final int M = 5; // margin		
		private final Color markerColor = new Color(255,255,0,150);
		private BufferedImage sliceImage, sideImage, frontImage, zoomImage;
		private int[] grays;
		
		public MapView() {
			super();
			this.addMouseListener(this);
			this.addMouseWheelListener(this);
			this.addMouseMotionListener(this);
			grays = new int[256];
			for (int i = 0; i < 256; i++) grays[i] = i | (i<<8) | (i<<16);
		}	
		
		public void clearMetrics() {
			metricsValid = false;
		}
		
		public void paintProgress(double d) {
			progress = d;
			if (d >= 0) {
				W = this.getWidth();
				H = this.getHeight();
				Rectangle rect = new Rectangle(0, H/2 - 20, W, 40);
				this.paintImmediately(rect);
			}
		}

		private void calcMetrics() {
			W = this.getWidth();
			H = this.getHeight();
			smallX0 = dataW + M;
			smallW = W - M - smallX0;
			smallH = (int)(smallW * zScale);
			if (dataH > 2 * smallH + M) {
				frontY0 = dataH - M - 2*smallH;
				sideY0 = dataH - smallH;
			}
			else {
				frontY0 = M;
				sideY0 = 2*M + smallH;
			}
			smallZScale = (double)smallH / dataN;
			smallXScale = (double)smallW / dataW;
			smallYScale = (double)smallW / dataH;
			zoomY0 = dataH + M;
			zoomH = H - M - zoomY0;
			sliceImage = new BufferedImage(dataW, dataH, BufferedImage.TYPE_INT_RGB);
			frontImage = new BufferedImage(smallW, smallH, BufferedImage.TYPE_INT_RGB);
			sideImage = new BufferedImage(smallW, smallH, BufferedImage.TYPE_INT_RGB);
			zoomImage = new BufferedImage(W-M+1, zoomH, BufferedImage.TYPE_INT_RGB);
			metricsValid = true;
		}

		@Override
		public void paint(Graphics gr) {
			Graphics2D g2 = (Graphics2D) gr;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			
			W = this.getWidth();
			H = this.getHeight();
			g2.setColor(Color.black);
			g2.fillRect(0, 0, W, H);
			
			if (progress >= 0) {
				g2.setColor(new Color(50,50,50));
				g2.fillRect(0, H/2 - 20, W, 40);
				int w = (int) (W * progress);
				g2.setColor(new Color(50,200,50));
				g2.fillRect(0, H/2 - 20, w, 40);
				return;
			}
			
			if (currentScan == null || data == null) return;
			
			if (!metricsValid) {
				calcMetrics();
			}
			
			// main view
			double graySpan = hiGray - loGray;
            for (int i = 0; i < dataW; i++) for (int j = 0; j < dataH; j++) {
				int v = data[i][j][currentSlice];
				int c = (int) (255 * (v - loGray) / graySpan);
				if (c < 0) c = 0;
				if (c > 255) c = 255;
                sliceImage.setRGB(i, j, grays[c]);
			}
            g2.drawImage(sliceImage, 0, 0, null);
            
            // zoomed view
            if (mouseX < dataW && mouseY < dataH) {
				for (int i = M; i < W-M; i++) for (int j = 0; j < zoomH; j++) {
					int x = mouseX + (i - W/2) / zoom;
					if (x < 0 || x >= dataW) {
						zoomImage.setRGB(i-M, j, grays[0]);
						continue;
					}
					int y = mouseY + (j - zoomH/2) / zoom;
					if (y < 0 || y >= dataH) {
						zoomImage.setRGB(i-M, j, grays[0]);
						continue;
					}
					
					int v = data[x][y][currentSlice];
					int c = (int) (255 * (v - loGray) / graySpan);
					if (c < 0) c = 0;
					if (c > 255) c = 255;
                    zoomImage.setRGB(i-M, j, grays[c]);
				}
			    g2.drawImage(zoomImage, M, zoomY0, null);

				g2.setColor(markerColor);
				int d = 10;
				int xc = W/2;
				int yc = zoomY0 + zoomH/2;
				g2.drawLine(xc-2*d, yc, xc-d, yc);
				g2.drawLine(xc+2*d, yc, xc+d, yc);
				g2.drawLine(xc, yc-2*d, xc, yc-d);
				g2.drawLine(xc, yc+2*d, xc, yc+d);
			}

			int ySlice = (int)(currentSlice * smallZScale);
			
			// front view
            for (int i = 0; i < smallW; i++) for (int j = 0; j < smallH; j++) {
				int x = (int)(i / smallXScale);
				if (x >= dataW) x = dataW-1;
				int slice = (int)(j / smallZScale);
				if (slice >= dataN) slice = dataN-1;
				int v = data[x][currentY][slice];
				int c = (int) (255 * (v - loGray) / graySpan);
				if (c < 0) c = 0;
				if (c > 255) c = 255;
                frontImage.setRGB(i, j, grays[c]);
			}
            g2.drawImage(frontImage, smallX0,frontY0, null);

			g2.setColor(markerColor);
			g2.drawLine(smallX0, frontY0 + ySlice, smallX0 + smallW, frontY0 + ySlice);
			int xPos = (int) (currentX * smallXScale);
			g2.drawLine(smallX0 + xPos, frontY0, smallX0 + xPos, frontY0 + smallH);
			
			// side view
            for (int i = 0; i < smallW; i++) for (int j = 0; j < smallH; j++) {
				int y = (int)(i / smallYScale);
				if (y >= dataH) y = dataH-1;
				int slice = (int)(j / smallZScale);
				if (slice >= dataN) slice = dataN-1;
				int v = data[currentX][y][slice];
				int c = (int) (255 * (v - loGray) / graySpan);
				if (c < 0) c = 0;
				if (c > 255) c = 255;
                sideImage.setRGB(i, j, grays[c]);
			}
            g2.drawImage(sideImage, smallX0, sideY0, null);
            for (int j = 0; j < smallH; j++) {
				int slice = (int)(j / smallZScale);
				if (slice >= dataN) slice = dataN-1;
				if (currentScan.slices.get(slice).nameToPolygons.containsKey(CONTOUR_TRUTH)) {
					g2.setColor(truthMarkerColor);
					g2.drawLine(smallX0 + smallW - 3*M, j + sideY0, smallX0 + smallW - M, j + sideY0);
				}
				if (currentScan.slices.get(slice).nameToPolygons.containsKey(CONTOUR_SOLUTION)) {
					g2.setColor(solutionMarkerColor);
					g2.drawLine(smallX0 + smallW - 5*M, j + sideY0, smallX0 + smallW - 3*M, j + sideY0);
				}
            }

			g2.setColor(markerColor);
			g2.drawLine(smallX0, sideY0 + ySlice, smallX0 + smallW, sideY0 + ySlice);
			int yPos = (int) (currentY * smallYScale);
			g2.drawLine(smallX0 + yPos, sideY0, smallX0 + yPos, sideY0 + smallH);
			
			String levelInfo = "";
			if (mouseX < dataW && mouseY < dataH) {
				levelInfo = " (" + data[mouseX][mouseY][currentSlice] + ")";
			}
			g2.setColor(Color.white);
			g2.setFont(font);
			int s1 = currentSlice + 1;
			g2.drawString("#" + s1 + levelInfo, 2*M, 2 * font.getSize());
			
			if (showTruthCb.isSelected()) {
				List<Polygon> truthPolygons = currentScan.slices.get(currentSlice).nameToPolygons.get(CONTOUR_TRUTH);
				if (truthPolygons != null) {
					for (Polygon p: truthPolygons) {
						drawPoly(p, g2, truthBorderColor, truthFillColor);
					}
				}
			}
			if (structureSelectorComboBox.getSelectedIndex() > 0) {
				String key = (String)structureSelectorComboBox.getSelectedItem();
				key = key.replace(SHOW_STRUCTURE_TEXT, "");
				List<Polygon> polygons = currentScan.slices.get(currentSlice).nameToPolygons.get(key);
				if (polygons != null) {
					for (Polygon p: polygons) {
						drawPoly(p, g2, extraBorderColor, extraFillColor);
					}
				}
			}
			if (showSolutionCb.isSelected()) {
				List<Polygon> solutionPolygons = currentScan.slices.get(currentSlice).nameToPolygons.get(CONTOUR_SOLUTION);
				if (solutionPolygons != null) {
					for (Polygon p: solutionPolygons) {
						drawPoly(p, g2, solutionBorderColor, solutionFillColor);
					}
				}
			}
		}

		private void drawPoly(Polygon p, Graphics2D g2, Color border, Color fill) {
			g2.setColor(border);
			g2.draw(p.shape);
			g2.setColor(fill);
			g2.fill(p.shape);
		}

		@Override
		public void mouseClicked(java.awt.event.MouseEvent e) {
			if (!metricsValid) return;
			
			boolean needRepaint = false;
			
			int x = e.getX();
			int y = e.getY();
			if (x < dataW && y < dataH) {
				currentX = x;
				currentY = y;
				needRepaint = true;
			}
			else if (x >= smallX0 && x < smallX0 + smallW) {
				if (y >= frontY0 && y < frontY0 + smallH) {
					currentX = (int)((x - smallX0) / smallXScale);
					currentSlice = (int)((y - frontY0) / smallZScale);
					needRepaint = true;
				}
				else if (y >= sideY0 && y < sideY0 + smallH) {
					currentY = (int)((x - smallX0) / smallYScale);
					currentSlice = (int)((y - sideY0) / smallZScale);
					needRepaint = true;
				}
			}
			
			if (needRepaint) repaintMap();
		}
		
		@Override
		public void mouseMoved(MouseEvent e) {
			if (!metricsValid) return;
			
			int x = e.getX();
			int y = e.getY();
			if (x < dataW && y < dataH) {
				mouseX = x;
				mouseY = y;
				repaintMap();
			}
		}
				
		@Override
		public void mouseDragged(MouseEvent e) {
			// nothing			
		}
		@Override
		public void mouseReleased(java.awt.event.MouseEvent e) {
			// nothing
		}
		@Override
		public void mouseEntered(java.awt.event.MouseEvent e) {
			setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		@Override
		public void mouseExited(java.awt.event.MouseEvent e) {
			setCursor(Cursor.getDefaultCursor());
		}
		@Override
		public void mousePressed(java.awt.event.MouseEvent e) {
			// nothing
		}

		@Override
		public void mouseWheelMoved(MouseWheelEvent e) {
			boolean changed = false;
			if (e.getWheelRotation() > 0 && currentSlice < dataN - 1) {
				currentSlice++;
				changed = true;
			}
			else if (e.getWheelRotation() < 0 && currentSlice > 0) {
				currentSlice--;
				changed = true;
			}
			
			if (changed) repaintMap();
		}
	} // class MapView
	
	@Override
	public void actionPerformed(ActionEvent e) {
		// check boxes clicked
		repaintMap();
	}
	
	@Override
	public void itemStateChanged(ItemEvent e) {
		if (e.getStateChange() == ItemEvent.SELECTED) {
			if (e.getSource() == scanSelectorComboBox) {
				// new image selected
				String id = (String) scanSelectorComboBox.getSelectedItem();
				if (!id.equals(currentScan.id)) {
					currentScan = idToScan.get(id);
					loadDetails(0);
					repaintMap();
				}
			}
			else if (e.getSource() == zoomSelectorComboBox) {
				int i = zoomSelectorComboBox.getSelectedIndex();
				zoom = zoomLevels[i];
				repaintMap();
			}
			else if (e.getSource() == structureSelectorComboBox) {
				repaintMap();
			}
		}
	}	

	@Override
	public void mouseClicked(MouseEvent e) {
		if (e.getSource() != logArea) return;
		Set<String> idSet = new HashSet<>();
		for (String id: scanIds) idSet.add(id);
		try {
			int lineIndex = logArea.getLineOfOffset(logArea.getCaretPosition());
			String text = logArea.getText();
			String[] lines = text.split("\n");
			String line = lines[lineIndex].trim();
			if (idSet.contains(line) && !line.equals(currentScan.id)) {
				currentScan = idToScan.get(line);
				scanSelectorComboBox.setSelectedItem(line);
				loadDetails(0);
				repaintMap();
			}
			else if (line.startsWith("#")) { // #xxx:
				try {
					line = line.substring(1);
					line = line.substring(0, line.indexOf(":"));
					int slice = Integer.parseInt(line) - 1;
					for (int i = lineIndex - 1; i > 0; i--) {
						line = lines[i].trim();
						if (idSet.contains(line)) {
							if (line.equals(currentScan.id)) {
								currentSlice = slice;
								repaintMap();
								break;
							}
							else {
								currentScan = idToScan.get(line);
								scanSelectorComboBox.setSelectedItem(line);
								loadDetails(slice);
								repaintMap();
								break;
							}
						}
					}
				}
				catch (Exception ex) {
					// nothing
				}
			}
		} 
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	@Override
	public void mousePressed(MouseEvent e) {}
	@Override
	public void mouseReleased(MouseEvent e) {}
	@Override
	public void mouseEntered(MouseEvent e) {}
	@Override
	public void mouseExited(MouseEvent e) {}
	
	private void log(String s) {
		if (logArea != null) logArea.append(s + "\n");
		//System.out.println(s);
	}
	
	@Override
	public void stateChanged(ChangeEvent e) {
		if (e.getSource() == loSlider) {
			loGray = sliderToGray(loSlider.getValue());
		}
		else if (e.getSource() == hiSlider) {
			hiGray = sliderToGray(hiSlider.getValue());
		}
		setGrayLevelText();
		repaintMap();		
	}
	
	private static DecimalFormat df; 
	private static DecimalFormat df6; 
	static {
		df = new DecimalFormat("0.###");
		df6 = new DecimalFormat("0.######");
		DecimalFormatSymbols dfs = new DecimalFormatSymbols();
		dfs.setDecimalSeparator('.');
		df.setDecimalFormatSymbols(dfs);
		df6.setDecimalFormatSymbols(dfs);		
	}

	/**
	 * Pretty print a double
	 */
	public static String f(double d) {
		return df.format(d);
	}
	public static String f6(double d) {
		return df6.format(d);
	}
	
	// Gets the lines of a text file at the given path 
	public static List<String> readTextLines(String path) {
		List<String> ret = new Vector<>();
		try {
			InputStream is = new FileInputStream(path);
	        InputStreamReader isr = new InputStreamReader(is, "UTF-8");
	        LineNumberReader lnr = new LineNumberReader(isr);
	        while (true) {
				String line = lnr.readLine();
				if (line == null) break;
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				ret.add(line);
			}
			lnr.close();
		} 
		catch (Exception e) {
			e.printStackTrace();
		}
		return ret;
	}
	
	private static void exit(String s) {
		System.out.println(s);
		System.exit(1);
	}

	private static StringBuilder sbBoxes = new StringBuilder();
	
	public static void main(String[] args) throws Exception {

		boolean setDefaults = true;
		for (int i = 0; i < args.length; i++) { // to change settings easily from Eclipse
			if (args[i].equals("-no-defaults")) setDefaults = false;
		}
		
		Visualizer v = new Visualizer();
		String dir;
		
		// These are just some default settings for local testing, can be ignored.
		
		// sample data
		dir = "../data/example_extracted_sample/";
		v.solutionPath = dir + "solution.csv";
		
		// training data
//		dir = "../data/example_extracted";
		
		// test data
//		dir = "../data/provisional_extracted/";
//		v.solutionPath = dir + "solution.csv";

		// test data no contours
//		dir = "../data/provisional_extracted_no_gt/";
//		v.solutionPath = dir + "solution.csv";
		
		// validation data
//		dir = "../data/system_extracted/";
		
		v.dataDir = dir;
		v.hasGui = true;
		
		if (setDefaults) {
			v.hasGui = true;
			v.dataDir = null;
			v.solutionPath = null;
			v.writeGroundTruth = false;
		}
		
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-data-dir")) v.dataDir = args[i+1];
			if (args[i].equals("-solution")) v.solutionPath = args[i+1];
			if (args[i].equals("-lo-gray")) v.loGray = Integer.parseInt(args[i+1]);
			if (args[i].equals("-hi-gray")) v.hiGray = Integer.parseInt(args[i+1]);
			if (args[i].equals("-no-gui")) v.hasGui = false;
		}
		
		if (v.dataDir == null) exit("Data directory not set.");
		
		v.setupGUI();
		//FileOutputStream trainBoxes = new FileOutputStream("test_boxes_full.json");
		FileOutputStream trainBoxes = new FileOutputStream("ignore.json");
		v.run();
		trainBoxes.write(sbBoxes.toString().getBytes());
		trainBoxes.close();
	}
}