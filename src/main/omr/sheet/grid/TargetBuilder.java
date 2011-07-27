//----------------------------------------------------------------------------//
//                                                                            //
//                         T a r g e t B u i l d e r                          //
//                                                                            //
//----------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">                          //
//  Copyright (C) Herve Bitteur 2000-2010. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.   //
//----------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet.grid;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.log.Logger;

import omr.score.ScoresManager;

import omr.selection.SheetLocationEvent;

import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.picture.Picture;

import omr.ui.view.RubberPanel;
import omr.ui.view.ScrollView;

import omr.util.HorizontalSide;
import static omr.util.HorizontalSide.*;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.media.jai.*;

/**
 * Class {@code TargetBuilder} is in charge of building a perfect definition
 * of target systems, staves and lines as well as the dewarp grid that allows to
 * transform the original image in to the perfect image.
 *
 * @author Hervé Bitteur
 */
public class TargetBuilder
{
    //~ Static fields/initializers ---------------------------------------------

    /** Specific application parameters */
    private static final Constants constants = new Constants();

    /** Usual logger utility */
    private static final Logger logger = Logger.getLogger(TargetBuilder.class);

    //~ Instance fields --------------------------------------------------------

    /** Related sheet */
    private final Sheet sheet;

    /** Companion in charge of staff lines */
    private final LinesRetriever linesRetriever;

    /** Companion in charge of bar lines */
    private final BarsRetriever barsRetriever;

    /** Target width */
    private double targetWidth;

    /** Target height */
    private double targetHeight;

    /** Transform from initial point to deskewed point */
    private AffineTransform at;

    /** The target page */
    private TargetPage targetPage;

    /** All target lines */
    private List<TargetLine> allTargetLines = new ArrayList<TargetLine>();

    /** Source points */
    private List<Point2D> srcPoints = new ArrayList<Point2D>();

    /** Destination points */
    private List<Point2D> dstPoints = new ArrayList<Point2D>();

    /** The dewarp grid */
    private Warp dewarpGrid;

    /** The dewarped image */
    private RenderedImage dewarpedImage;

    //~ Constructors -----------------------------------------------------------

    //---------------//
    // TargetBuilder //
    //---------------//
    /**
     * Creates a new TargetBuilder object.
     *
     * @param sheet DOCUMENT ME!
     * @param linesRetriever DOCUMENT ME!
     * @param barsRetriever DOCUMENT ME!
     */
    public TargetBuilder (Sheet          sheet,
                          LinesRetriever linesRetriever,
                          BarsRetriever  barsRetriever)
    {
        this.sheet = sheet;
        this.linesRetriever = linesRetriever;
        this.barsRetriever = barsRetriever;
    }

    //~ Methods ----------------------------------------------------------------

    //-----------//
    // buildInfo //
    //-----------//
    public void buildInfo ()
    {
        buildTarget();
        buildWarpGrid();

        // Dewarp the initial image
        dewarpImage();

        // Add a view on dewarped image
        sheet.getAssembly()
             .addViewTab(
            "DeWarped",
            new ScrollView(new DewarpedView(dewarpedImage)),
            null);

        // Store dewarped image on disk
        if (constants.storeDewarp.getValue()) {
            storeImage();
        }
    }

    //---------------//
    // renderSystems //
    //---------------//
    /**
     * TODO: This should be done from a more central class
     * @param g graphical context
     */
    public void renderSystems (Graphics2D g)
    {
        if ((barsRetriever == null) || (barsRetriever.getSystems() == null)) {
            return;
        }

        Stroke systemStroke = new BasicStroke(
            0.5f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND);

        g.setStroke(systemStroke);
        g.setColor(Color.YELLOW);

        for (SystemFrame system : barsRetriever.getSystems()) {
            for (HorizontalSide side : HorizontalSide.values()) {
                Point2D top = system.getFirstStaff()
                                    .getFirstLine()
                                    .getEndPoint(side);
                Point2D bot = system.getLastStaff()
                                    .getLastLine()
                                    .getEndPoint(side);
                g.draw(new Line2D.Double(top, bot));
            }
        }
    }

    //----------------//
    // renderWarpGrid //
    //----------------//
    /**
     * Render the grid used to dewarp the sheet image
     * @param g the graphic context
     * @param useSource true to render the source grid, false to render the
     * destination grid
     */
    public void renderWarpGrid (Graphics g,
                                boolean  useSource)
    {
        if (!constants.displayGrid.getValue()) {
            return;
        }

        Graphics2D    g2 = (Graphics2D) g;
        List<Point2D> points = useSource ? srcPoints : dstPoints;
        double        radius = sheet.getScale()
                                    .toPixelsDouble(constants.gridPointSize);
        g2.setColor(Color.RED);

        Rectangle2D rect = new Rectangle2D.Double();

        for (Point2D pt : points) {
            rect.setRect(
                pt.getX() - radius,
                pt.getY() - radius,
                2 * radius,
                2 * radius);
            g2.fill(rect);
        }
    }

    //-------------//
    // buildTarget //
    //-------------//
    /**
     * Build a perfect definition of target page, systems, staves and lines.
     *
     * We apply a rotation on every top-left corner
     */
    private void buildTarget ()
    {
        // Set up rotation + origin translation
        computeDeskew();

        // Target page parameters
        targetPage = new TargetPage(targetWidth, targetHeight);

        TargetLine prevLine = null;

        // Target system parameters
        for (SystemFrame system : barsRetriever.getSystems()) {
            StaffInfo firstStaff = system.getFirstStaff();
            LineInfo  firstLine = firstStaff.getFirstLine();
            Point2D   dskLeft = deskew(firstLine.getEndPoint(LEFT));
            Point2D   dskRight = deskew(firstLine.getEndPoint(RIGHT));

            if (prevLine != null) {
                // Preserve position relative to bottom right of previous system
                Point2D      prevDskRight = deskew(
                    prevLine.info.getEndPoint(RIGHT));
                TargetSystem prevSystem = prevLine.staff.system;
                double       dx = prevSystem.right - prevDskRight.getX();
                double       dy = prevLine.y - prevDskRight.getY();
                dskRight.setLocation(
                    dskRight.getX() + dx,
                    dskRight.getY() + dy);
                dskLeft.setLocation(dskLeft.getX() + dx, dskLeft.getY() + dy);
            }

            TargetSystem targetSystem = new TargetSystem(
                system,
                dskRight.getY(),
                dskLeft.getX(),
                dskRight.getX());
            targetPage.systems.add(targetSystem);

            // Target staff parameters
            for (StaffInfo staff : system.getStaves()) {
                dskRight = deskew(staff.getFirstLine().getEndPoint(RIGHT));

                if (prevLine != null) {
                    // Preserve inter-staff vertical gap
                    Point2D prevDskRight = deskew(
                        prevLine.info.getEndPoint(RIGHT));
                    dskRight.setLocation(
                        dskRight.getX(),
                        dskRight.getY() + (prevLine.y - prevDskRight.getY()));
                }

                TargetStaff targetStaff = new TargetStaff(
                    staff,
                    dskRight.getY(),
                    targetSystem);
                targetSystem.staves.add(targetStaff);

                // Target line parameters
                int lineIdx = -1;

                for (LineInfo line : staff.getLines()) {
                    lineIdx++;

                    // Enforce perfect staff interline
                    TargetLine targetLine = new TargetLine(
                        line,
                        targetStaff.top +
                        (staff.getSpecificScale().interline() * lineIdx),
                        targetStaff);
                    allTargetLines.add(targetLine);
                    targetStaff.lines.add(targetLine);
                    prevLine = targetLine;
                }
            }
        }
    }

    //---------------//
    // buildWarpGrid //
    //---------------//
    private void buildWarpGrid ()
    {
        int xStep = sheet.getInterline();
        int xNumCells = (int) Math.ceil(sheet.getWidth() / (double) xStep);
        int yStep = sheet.getInterline();
        int yNumCells = (int) Math.ceil(sheet.getHeight() / (double) yStep);

        for (int ir = 0; ir <= yNumCells; ir++) {
            for (int ic = 0; ic <= xNumCells; ic++) {
                Point2D dst = new Point2D.Double(ic * xStep, ir * yStep);
                dstPoints.add(dst);

                Point2D src = sourceOf(dst);
                srcPoints.add(src);
            }
        }

        float[] warpPositions = new float[srcPoints.size() * 2];
        int     i = 0;

        for (Point2D p : srcPoints) {
            warpPositions[i++] = (float) p.getX();
            warpPositions[i++] = (float) p.getY();
        }

        dewarpGrid = new WarpGrid(
            0,
            xStep,
            xNumCells,
            0,
            yStep,
            yNumCells,
            warpPositions);
    }

    //---------------//
    // computeDeskew //
    //---------------//
    private void computeDeskew ()
    {
        double globalSlope = linesRetriever.getGlobalSlope();
        double deskewAngle = -Math.atan(globalSlope);
        at = AffineTransform.getRotateInstance(deskewAngle);

        // Compute topLeft origin translation
        int     w = sheet.getWidth();
        int     h = sheet.getHeight();
        Point2D topRight = at.transform(new Point(w, 0), null);
        Point2D bottomLeft = at.transform(new Point(0, h), null);
        Point2D bottomRight = at.transform(new Point(w, h), null);
        double  dx = 0;
        double  dy = 0;

        if (deskewAngle <= 0) { // Counter-clockwise deskew
            targetWidth = bottomRight.getX();
            dy = -topRight.getY();
            targetHeight = bottomLeft.getY() + dy;
        } else { // Clockwise deskew
            dx = -bottomLeft.getX();
            targetWidth = topRight.getX() + dx;
            targetHeight = bottomRight.getY();
        }

        at.translate(dx, dy);
    }

    //--------//
    // deskew //
    //--------//
    /**
     * Apply rotation OPPOSITE to the measured global angle and use the new
     * origin
     *
     * @param pt the initial (skewed) point
     * @return the deskewed point
     */
    private Point2D deskew (Point2D pt)
    {
        return at.transform(pt, null);
    }

    //-------------//
    // dewarpImage //
    //-------------//
    private void dewarpImage ()
    {
        ParameterBlock pb = new ParameterBlock();
        pb.addSource(Picture.invert(sheet.getPicture().getImage()));
        pb.add(dewarpGrid);
        pb.add(new InterpolationBilinear());

        dewarpedImage = Picture.invert(JAI.create("warp", pb));
        ((PlanarImage) dewarpedImage).getTiles();
    }

    //----------//
    // sourceOf //
    //----------//
    /**
     * This key method provides the source point (in original sheet image)
     * that corresponds to a given destination point (in target dewarped image).
     *
     * The strategy is to stay consistent with the staff lines nearby which
     * are used as grid references.
     *
     * @param dst the given destination point
     * @return the corresponding source point
     */
    private Point2D sourceOf (Point2D dst)
    {
        double     dstX = dst.getX();
        double     dstY = dst.getY();

        // Retrieve north & south lines, if any
        TargetLine northLine = null;
        TargetLine southLine = null;

        for (TargetLine line : allTargetLines) {
            if (line.y <= dstY) {
                northLine = line;
            } else {
                southLine = line;

                break;
            }
        }

        // Case of image top: no northLine
        if (northLine == null) {
            return southLine.sourceOf(dst);
        }

        // Case of image bottom: no southLine
        if (southLine == null) {
            return northLine.sourceOf(dst);
        }

        // Normal case: use y barycenter between projections sources
        Point2D srcNorth = northLine.sourceOf(dstX);
        Point2D srcSouth = southLine.sourceOf(dstX);
        double  yRatio = (dstY - northLine.y) / (southLine.y - northLine.y);

        return new Point2D.Double(
            ((1 - yRatio) * srcNorth.getX()) + (yRatio * srcSouth.getX()),
            ((1 - yRatio) * srcNorth.getY()) + (yRatio * srcSouth.getY()));
    }

    //------------//
    // storeImage //
    //------------//
    private void storeImage ()
    {
        String pageId = sheet.getPage()
                             .getId();
        File   file = new File(
            ScoresManager.getInstance().getDefaultDewarpDirectory(),
            pageId + ".dewarped.png");

        try {
            String path = file.getCanonicalPath();
            ImageIO.write(dewarpedImage, "png", file);
            logger.info("Wrote " + path);
        } catch (IOException ex) {
            logger.warning("Could not write " + file);
        }
    }

    //~ Inner Classes ----------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
        extends ConstantSet
    {
        //~ Instance fields ----------------------------------------------------

        Constant.Boolean   displayGrid = new Constant.Boolean(
            true,
            "Should we display the dewarp grid?");

        //
        Scale.LineFraction gridPointSize = new Scale.LineFraction(
            0.2,
            "Size of displayed grid points");

        //
        Constant.Boolean storeDewarp = new Constant.Boolean(
            false,
            "Should we store the dewarped image on disk?");
    }

    //--------------//
    // DewarpedView //
    //--------------//
    private class DewarpedView
        extends RubberPanel
    {
        //~ Instance fields ----------------------------------------------------

        private final AffineTransform identity = new AffineTransform();
        private final RenderedImage   image;

        //~ Constructors -------------------------------------------------------

        public DewarpedView (RenderedImage image)
        {
            this.image = image;

            setModelSize(new Dimension(image.getWidth(), image.getHeight()));

            // Location service
            setLocationService(
                sheet.getSelectionService(),
                SheetLocationEvent.class);

            setName("DewarpedView");
        }

        //~ Methods ------------------------------------------------------------

        @Override
        public void render (Graphics2D g)
        {
            // Display the dewarped image
            g.drawRenderedImage(image, identity);

            // Display also the Destination Points
            renderWarpGrid(g, false);
        }
    }
}