/*
 This file is part of jpcsp.

 Jpcsp is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Jpcsp is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp;

import static jpcsp.Allegrex.compiler.RuntimeContext.setLog4jMDC;
import static jpcsp.graphics.VideoEngineUtilities.getResizedHeight;
import static jpcsp.graphics.VideoEngineUtilities.getResizedWidth;
import static jpcsp.graphics.VideoEngineUtilities.getViewportResizeScaleFactor;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.Security;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;

import javax.swing.*;

import jpcsp.Allegrex.compiler.Profiler;
import jpcsp.Allegrex.compiler.RuntimeContext;
import jpcsp.Allegrex.compiler.RuntimeContextLLE;
import jpcsp.autotests.AutoTestsRunner;
import jpcsp.crypto.AES128;
import jpcsp.crypto.PreDecrypt;
import jpcsp.Debugger.ElfHeaderInfo;
import jpcsp.Debugger.ImageViewer;
import jpcsp.Debugger.InstructionCounter;
import jpcsp.Debugger.MemoryViewer;
import jpcsp.Debugger.StepLogger;
import jpcsp.Debugger.DisassemblerModule.DisassemblerFrame;
import jpcsp.Debugger.DisassemblerModule.VfpuFrame;
import jpcsp.GUI.CheatsGUI;
import jpcsp.GUI.IMainGUI;
import jpcsp.GUI.RecentElement;
import jpcsp.GUI.SettingsGUI;
import jpcsp.GUI.ControlsGUI;
import jpcsp.GUI.LogGUI;
import jpcsp.GUI.UmdBrowser;
import jpcsp.GUI.UmdVideoPlayer;
import jpcsp.HLE.HLEModuleManager;
import jpcsp.HLE.Modules;
import jpcsp.HLE.VFS.local.LocalVirtualFile;
import jpcsp.HLE.kernel.types.SceKernelThreadInfo;
import jpcsp.HLE.kernel.types.SceModule;
import jpcsp.HLE.modules.IoFileMgrForUser;
import jpcsp.HLE.modules.reboot;
import jpcsp.HLE.modules.sceDisplay;
import jpcsp.HLE.modules.sceUtility;
import jpcsp.filesystems.SeekableDataInput;
import jpcsp.filesystems.SeekableRandomFile;
import jpcsp.filesystems.umdiso.UmdIsoFile;
import jpcsp.filesystems.umdiso.UmdIsoReader;
import jpcsp.format.PBP;
import jpcsp.format.PSF;
import jpcsp.graphics.GEProfiler;
import jpcsp.graphics.VideoEngine;
import jpcsp.graphics.VideoEngineUtilities;
import jpcsp.hardware.Audio;
import jpcsp.hardware.MemoryStick;
import jpcsp.hardware.Screen;
import jpcsp.hardware.Wlan;
import jpcsp.log.LogWindow;
import jpcsp.log.LoggingOutputStream;
import jpcsp.memory.DebuggerMemory;
import jpcsp.memory.mmio.umd.MMIOHandlerUmd;
import jpcsp.network.AutoDetectLocalIPAddress;
import jpcsp.network.proonline.ProOnlineNetworkAdapter;
import jpcsp.network.xlinkkai.XLinkKaiWlanAdapter;
import jpcsp.remote.HTTPServer;
import jpcsp.settings.Settings;
import jpcsp.util.FileUtil;
import jpcsp.util.JpcspDialogManager;
import jpcsp.util.LWJGLFixer;
import jpcsp.util.MetaInformation;
import jpcsp.util.Utilities;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;

import com.jidesoft.plaf.LookAndFeelFactory;

import jpcsp.Debugger.FileLogger.FileLoggerFrame;

/**
 *
 * @author shadow
 */
public class MainGUI extends javax.swing.JFrame implements KeyListener, ComponentListener, MouseListener, IMainGUI {
    static {
        LWJGLFixer.fixOnce();
    }

    private static final long serialVersionUID = -3647025845406693230L;
    private static Logger log = Emulator.log;
    public static final int MAX_RECENT = 10;
    Emulator emulator;
    UmdBrowser umdbrowser;
    UmdVideoPlayer umdvideoplayer;
    InstructionCounter instructioncounter;
    File loadedFile;
    boolean umdLoaded;
    boolean useFullscreen;
    JPopupMenu fullScreenMenu;
    private List<RecentElement> recentUMD = new LinkedList<RecentElement>();
    private List<RecentElement> recentFile = new LinkedList<RecentElement>();
    private final static String[] userDir = {
        "ms0/PSP/SAVEDATA",
        "ms0/PSP/GAME",
        "tmp"
    };
    private static final String logConfigurationSettingLeft = "    %1$-40s %3$c%2$s%4$c";
    private static final String logConfigurationSettingRight = "    %3$c%2$s%4$c %1$s";
    private static final String logConfigurationSettingLeftPatch = "    %1$-40s %3$c%2$s%4$c (%5$s)";
    private static final String logConfigurationSettingRightPatch = "    %3$c%2$s%4$c %1$s (%5$s)";
    public static final int displayModeBitDepth = 32;
    public static final int preferredDisplayModeRefreshRate = 60; // Preferred refresh rate if 60Hz
    private DisplayMode displayMode;
    private SetLocationThread setLocationThread;
    private JComponent fillerLeft;
    private JComponent fillerRight;
    private JComponent fillerTop;
    private JComponent fillerBottom;
    private static boolean jideInitialized;
    // map to hold action listeners for menu entries in fullscreen mode
    private HashMap<KeyStroke, ActionListener[]> actionListenerMap;
    private boolean doUmdBuffering = false;
    private boolean runFromVsh = false;
    private String stateFileName = null;

    @Override
    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    /**
     * Creates new form MainGUI
     */
    public MainGUI() {
        System.setOut(new PrintStream(new LoggingOutputStream(Logger.getLogger("emu"), Level.INFO)));

        actionListenerMap = new HashMap<KeyStroke, ActionListener[]>();

        // create log window in a local variable - see explanation further down
        LogWindow logwin = new LogWindow();

        // create needed user directories
        for (String dirName : userDir) {
            File dir = new File(dirName);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        emulator = new Emulator(this);
        Screen.start();

        // must be done after initialising the Emulator class as State initialises
        // its elements indirectly via getting the pointer to MainGUI by means
        // of the Emulator class...
        State.logWindow = logwin;

        // next two lines are for overlay menus over joglcanvas
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);

        useFullscreen = Settings.getInstance().readBool("gui.fullscreen");
        if (useFullscreen && !isDisplayable()) {
            setUndecorated(true);
            setLocation(0, 0);
            setSize(getFullScreenDimension());
            setPreferredSize(getFullScreenDimension());
        }

        String resolution = Settings.getInstance().readString("emu.graphics.resolution");
        if (resolution != null && resolution.contains("x")) {
            int width = Integer.parseInt(resolution.split("x")[0]);
            int heigth = Integer.parseInt(resolution.split("x")[1]);
            changeScreenResolution(width, heigth);
        }

        initJide();
        createComponents();

        onUmdChange();

        setTitle(MetaInformation.FULL_NAME);

        // add glcanvas to frame and pack frame to get the canvas size
        getContentPane().add(Modules.sceDisplayModule.getCanvas(), java.awt.BorderLayout.CENTER);
        Modules.sceDisplayModule.getCanvas().addKeyListener(this);
        Modules.sceDisplayModule.getCanvas().addMouseListener(this);
        addComponentListener(this);
        pack();
        
        // Check if any plugins are available.
        xbrzCheck.setEnabled(false);
        String libraryPath = System.getProperty("java.library.path");
        if (libraryPath != null && libraryPath.length() > 0) {
        	String[] paths = libraryPath.split(File.pathSeparator);
        	for (String path : paths) {
		        File plugins = new File(path);
		        String[] pluginList = plugins.list();
		        if (pluginList != null) {
			        for (String list : pluginList) {
			            if (list.contains("XBRZ4JPCSP")) {
			                xbrzCheck.setEnabled(true);
			            }
			        }
		        }
        	}
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                // let the layout manager settle before setting the minimum size
                VideoEngineUtilities.setDisplayMinimumSize();

                // as the console log window position depends on the main
                // window's size run this here
                if (Settings.getInstance().readBool("gui.snapLogwindow")) {
                    updateConsoleWinPosition();
                }
            }
        });

        WindowPropSaver.loadWindowProperties(this);

        try {
            Image iconImage = new ImageIcon(ClassLoader.getSystemClassLoader().getResource("jpcsp/icon.png")).getImage();
            this.setIconImages(Arrays.asList(
                    iconImage.getScaledInstance(16, 16, Image.SCALE_SMOOTH),
                    iconImage.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
                    iconImage
            ));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private Dimension getDimensionFromDisplay(int width, int height) {
        Insets insets = getInsets();
        Dimension dimension = new Dimension(
                width + insets.left + insets.right,
                height + insets.top + insets.bottom);
        return dimension;
    }

    @Override
    public void setDisplayMinimumSize(int width, int height) {
        Dimension dim = getDimensionFromDisplay(width, height);
        dim.height += mainToolBar.getHeight();
        dim.height += MenuBar.getHeight();
    	Dimension currentMinimumSize = getMinimumSize();
    	if (currentMinimumSize == null || !dim.equals(currentMinimumSize)) {
    		setMinimumSize(dim);
    	}
    }

    @Override
    public void setDisplaySize(int width, int height) {
    	if (width != getWidth() || height != getHeight()) {
    		setSize(getDimensionFromDisplay(width, height));
    	}
    }

    private void initJide() {
        if (!jideInitialized) {
            LookAndFeelFactory.installJideExtension(LookAndFeelFactory.VSNET_STYLE_WITHOUT_MENU);
            jideInitialized = true;
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        filtersGroup = new javax.swing.ButtonGroup();
        resGroup = new javax.swing.ButtonGroup();
        frameSkipGroup = new javax.swing.ButtonGroup();
        clockSpeedGroup = new javax.swing.ButtonGroup();
        mainToolBar = new javax.swing.JToolBar();
        RunButton = new javax.swing.JToggleButton();
        PauseButton = new javax.swing.JToggleButton();
        ResetButton = new javax.swing.JButton();
        MenuBar = new javax.swing.JMenuBar();
        FileMenu = new javax.swing.JMenu();
        openUmd = new javax.swing.JMenuItem();
        OpenFile = new javax.swing.JMenuItem();
        RecentMenu = new javax.swing.JMenu();
        switchUmd = new javax.swing.JMenuItem();
        ejectMs = new javax.swing.JMenuItem();
        jSeparator2 = new javax.swing.JSeparator();
        SaveSnap = new javax.swing.JMenuItem();
        LoadSnap = new javax.swing.JMenuItem();
        ExportMenu = new javax.swing.JMenu();
        ExportVisibleElements = new javax.swing.JMenuItem();
        ExportAllElements = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JSeparator();
        ExitEmu = new javax.swing.JMenuItem();
        OptionsMenu = new javax.swing.JMenu();
        VideoOpt = new javax.swing.JMenu();
        ResizeMenu = new javax.swing.JMenu();
        oneTimeResize = new javax.swing.JCheckBoxMenuItem();
        twoTimesResize = new javax.swing.JCheckBoxMenuItem();
        threeTimesResize = new javax.swing.JCheckBoxMenuItem();
        FiltersMenu = new javax.swing.JMenu();
        noneCheck = new javax.swing.JCheckBoxMenuItem();
        anisotropicCheck = new javax.swing.JCheckBoxMenuItem();
        FrameSkipMenu = new javax.swing.JMenu();
        FrameSkipNone = new javax.swing.JCheckBoxMenuItem();
        FPS5 = new javax.swing.JCheckBoxMenuItem();
        FPS10 = new javax.swing.JCheckBoxMenuItem();
        FPS15 = new javax.swing.JCheckBoxMenuItem();
        FPS20 = new javax.swing.JCheckBoxMenuItem();
        FPS30 = new javax.swing.JCheckBoxMenuItem();
        FPS60 = new javax.swing.JCheckBoxMenuItem();
        ShotItem = new javax.swing.JMenuItem();
        RotateItem = new javax.swing.JMenuItem();
        AudioOpt = new javax.swing.JMenu();
        MuteOpt = new javax.swing.JCheckBoxMenuItem();
        ClockSpeedOpt = new javax.swing.JMenu();
        ClockSpeed50 = new javax.swing.JCheckBoxMenuItem();
        ClockSpeed75 = new javax.swing.JCheckBoxMenuItem();
        ClockSpeedNormal = new javax.swing.JCheckBoxMenuItem();
        ClockSpeed150 = new javax.swing.JCheckBoxMenuItem();
        ClockSpeed200 = new javax.swing.JCheckBoxMenuItem();
        ClockSpeed300 = new javax.swing.JCheckBoxMenuItem();
        ControlsConf = new javax.swing.JMenuItem();
        ConfigMenu = new javax.swing.JMenuItem();
        DebugMenu = new javax.swing.JMenu();
        ToolsSubMenu = new javax.swing.JMenu();
        LoggerMenu = new javax.swing.JMenu();
        ToggleLogger = new javax.swing.JCheckBoxMenuItem();
        CustomLogger = new javax.swing.JMenuItem();
        EnterDebugger = new javax.swing.JMenuItem();
        EnterMemoryViewer = new javax.swing.JMenuItem();
        EnterImageViewer = new javax.swing.JMenuItem();
        VfpuRegisters = new javax.swing.JMenuItem();
        ElfHeaderViewer = new javax.swing.JMenuItem();
        FileLog = new javax.swing.JMenuItem();
        InstructionCounter = new javax.swing.JMenuItem();
        DumpIso = new javax.swing.JMenuItem();
        ResetProfiler = new javax.swing.JMenuItem();
        ClearTextureCache = new javax.swing.JMenuItem();
        ClearVertexCache = new javax.swing.JMenuItem();
        ExportISOFile = new javax.swing.JMenuItem();
        CheatsMenu = new javax.swing.JMenu();
        cwcheat = new javax.swing.JMenuItem();
        LanguageMenu = new javax.swing.JMenu();
        SystemLocale = new javax.swing.JMenuItem();
        EnglishGB = new javax.swing.JMenuItem();
        EnglishUS = new javax.swing.JMenuItem();
        French = new javax.swing.JMenuItem();
        German = new javax.swing.JMenuItem();
        Lithuanian = new javax.swing.JMenuItem();
        Spanish = new javax.swing.JMenuItem();
        Catalan = new javax.swing.JMenuItem();
        Portuguese = new javax.swing.JMenuItem();
        PortugueseBR = new javax.swing.JMenuItem();
        Japanese = new javax.swing.JMenuItem();
        Russian = new javax.swing.JMenuItem();
        Polish = new javax.swing.JMenuItem();
        ChinesePRC = new javax.swing.JMenuItem();
        ChineseTW = new javax.swing.JMenuItem();
        Italian = new javax.swing.JMenuItem();
        Greek = new javax.swing.JMenuItem();
        PluginsMenu = new javax.swing.JMenu();
        xbrzCheck = new javax.swing.JCheckBoxMenuItem();
        HelpMenu = new javax.swing.JMenu();
        About = new javax.swing.JMenuItem();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
        setForeground(java.awt.Color.white);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent evt) {
                formComponentResized(evt);
            }
        });

        mainToolBar.setFloatable(false);
        mainToolBar.setRollover(true);

        RunButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/PlayIcon.png"))); // NOI18N
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp"); // NOI18N
        RunButton.setText(bundle.getString("MainGUI.RunButton.text")); // NOI18N
        RunButton.setFocusable(false);
        RunButton.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
        RunButton.setIconTextGap(2);
        RunButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        RunButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                RunButtonActionPerformed(evt);
            }
        });
        mainToolBar.add(RunButton);

        PauseButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/PauseIcon.png"))); // NOI18N
        PauseButton.setText(bundle.getString("MainGUI.PauseButton.text")); // NOI18N
        PauseButton.setFocusable(false);
        PauseButton.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
        PauseButton.setIconTextGap(2);
        PauseButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        PauseButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                PauseButtonActionPerformed(evt);
            }
        });
        mainToolBar.add(PauseButton);

        ResetButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/StopIcon.png"))); // NOI18N
        ResetButton.setText(bundle.getString("MainGUI.ResetButton.text")); // NOI18N
        ResetButton.setFocusable(false);
        ResetButton.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
        ResetButton.setIconTextGap(2);
        ResetButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        ResetButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ResetButtonActionPerformed(evt);
            }
        });
        mainToolBar.add(ResetButton);

        getContentPane().add(mainToolBar, java.awt.BorderLayout.NORTH);

        FileMenu.setText(bundle.getString("MainGUI.FileMenu.text")); // NOI18N

        openUmd.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_MASK));
        openUmd.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/LoadUmdIcon.png"))); // NOI18N
        openUmd.setText(bundle.getString("MainGUI.openUmd.text")); // NOI18N
        openUmd.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openUmdActionPerformed(evt);
            }
        });
        FileMenu.add(openUmd);

        OpenFile.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.ALT_MASK));
        OpenFile.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/LoadFileIcon.png"))); // NOI18N
        OpenFile.setText(bundle.getString("MainGUI.OpenFile.text")); // NOI18N
        OpenFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                OpenFileActionPerformed(evt);
            }
        });
        FileMenu.add(OpenFile);

        RecentMenu.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/RecentIcon.png"))); // NOI18N
        RecentMenu.setText(bundle.getString("MainGUI.RecentMenu.text")); // NOI18N
        FileMenu.add(RecentMenu);

        switchUmd.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/LoadUmdIcon.png"))); // NOI18N
        switchUmd.setText(bundle.getString("MainGUI.switchUmd.text")); // NOI18N
        switchUmd.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                switchUmdActionPerformed(evt);
            }
        });
        FileMenu.add(switchUmd);

        ejectMs.setText(bundle.getString("MainGUI.ejectMs.text")); // NOI18N
        ejectMs.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ejectMsActionPerformed(evt);
            }
        });
        FileMenu.add(ejectMs);
        FileMenu.add(jSeparator2);

        SaveSnap.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.SHIFT_MASK));
        SaveSnap.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/SaveStateIcon.png"))); // NOI18N
        SaveSnap.setText(bundle.getString("MainGUI.SaveSnap.text")); // NOI18N
        SaveSnap.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                SaveSnapActionPerformed(evt);
            }
        });
        FileMenu.add(SaveSnap);

        LoadSnap.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_L, java.awt.event.InputEvent.SHIFT_MASK));
        LoadSnap.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/LoadStateIcon.png"))); // NOI18N
        LoadSnap.setText(bundle.getString("MainGUI.LoadSnap.text")); // NOI18N
        LoadSnap.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                LoadSnapActionPerformed(evt);
            }
        });
        FileMenu.add(LoadSnap);

        ExportMenu.setText(bundle.getString("MainGUI.ExportMenu.text")); // NOI18N

        ExportVisibleElements.setText(bundle.getString("MainGUI.ExportVisibleElements.text")); // NOI18N
        ExportVisibleElements.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ExportVisibleElementsActionPerformed(evt);
            }
        });
        ExportMenu.add(ExportVisibleElements);

        ExportAllElements.setText(bundle.getString("MainGUI.ExportAllElements.text")); // NOI18N
        ExportAllElements.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ExportAllElementsActionPerformed(evt);
            }
        });
        ExportMenu.add(ExportAllElements);

        FileMenu.add(ExportMenu);
        FileMenu.add(jSeparator1);

        ExitEmu.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E, java.awt.event.InputEvent.CTRL_MASK));
        ExitEmu.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/CloseIcon.png"))); // NOI18N
        ExitEmu.setText(bundle.getString("MainGUI.ExitEmu.text")); // NOI18N
        ExitEmu.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ExitEmuActionPerformed(evt);
            }
        });
        FileMenu.add(ExitEmu);

        MenuBar.add(FileMenu);

        OptionsMenu.setText(bundle.getString("MainGUI.OptionsMenu.text")); // NOI18N

        VideoOpt.setText(bundle.getString("MainGUI.VideoOpt.text")); // NOI18N

        ResizeMenu.setText(bundle.getString("MainGUI.ResizeMenu.text")); // NOI18N

        resGroup.add(oneTimeResize);
        oneTimeResize.setSelected(true);
        oneTimeResize.setText("1x"); // NOI18N
        oneTimeResize.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                oneTimeResizeActionPerformed(evt);
            }
        });
        ResizeMenu.add(oneTimeResize);

        resGroup.add(twoTimesResize);
        twoTimesResize.setText("2x"); // NOI18N
        twoTimesResize.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                twoTimesResizeActionPerformed(evt);
            }
        });
        ResizeMenu.add(twoTimesResize);

        resGroup.add(threeTimesResize);
        threeTimesResize.setText("3x"); // NOI18N
        threeTimesResize.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                threeTimesResizeActionPerformed(evt);
            }
        });
        ResizeMenu.add(threeTimesResize);

        VideoOpt.add(ResizeMenu);

        FiltersMenu.setText(bundle.getString("MainGUI.FiltersMenu.text")); // NOI18N

        filtersGroup.add(noneCheck);
        noneCheck.setSelected(true);
        noneCheck.setText(bundle.getString("MainGUI.noneCheck.text")); // NOI18N
        noneCheck.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                noneCheckActionPerformed(evt);
            }
        });
        FiltersMenu.add(noneCheck);

        filtersGroup.add(anisotropicCheck);
        anisotropicCheck.setSelected(Settings.getInstance().readBool("emu.graphics.filters.anisotropic"));
        anisotropicCheck.setText(bundle.getString("MainGUI.anisotropicCheck.text")); // NOI18N
        anisotropicCheck.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                anisotropicCheckActionPerformed(evt);
            }
        });
        FiltersMenu.add(anisotropicCheck);

        VideoOpt.add(FiltersMenu);

        FrameSkipMenu.setText(bundle.getString("MainGUI.FrameSkipMenu.text")); // NOI18N

        frameSkipGroup.add(FrameSkipNone);
        FrameSkipNone.setSelected(true);
        FrameSkipNone.setText(bundle.getString("MainGUI.FrameSkipNone.text")); // NOI18N
        FrameSkipNone.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                frameSkipNoneActionPerformed(evt);
            }
        });
        FrameSkipMenu.add(FrameSkipNone);

        frameSkipGroup.add(FPS5);
        FPS5.setText("5 FPS"); // NOI18N
        FPS5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                frameSkipFPS5ActionPerformed(evt);
            }
        });
        FrameSkipMenu.add(FPS5);

        frameSkipGroup.add(FPS10);
        FPS10.setText("10 FPS"); // NOI18N
        FPS10.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                frameSkipFPS10ActionPerformed(evt);
            }
        });
        FrameSkipMenu.add(FPS10);

        frameSkipGroup.add(FPS15);
        FPS15.setText("15 FPS"); // NOI18N
        FPS15.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                frameSkipFPS15ActionPerformed(evt);
            }
        });
        FrameSkipMenu.add(FPS15);

        frameSkipGroup.add(FPS20);
        FPS20.setText("20 FPS"); // NOI18N
        FPS20.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                frameSkipFPS20ActionPerformed(evt);
            }
        });
        FrameSkipMenu.add(FPS20);

        frameSkipGroup.add(FPS30);
        FPS30.setText("30 FPS"); // NOI18N
        FPS30.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                frameSkipFPS30ActionPerformed(evt);
            }
        });
        FrameSkipMenu.add(FPS30);

        frameSkipGroup.add(FPS60);
        FPS60.setText("60 FPS"); // NOI18N
        FPS60.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                frameSkipFPS60ActionPerformed(evt);
            }
        });
        FrameSkipMenu.add(FPS60);

        VideoOpt.add(FrameSkipMenu);

        ShotItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F5, 0));
        ShotItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/ScreenshotIcon.png"))); // NOI18N
        ShotItem.setText(bundle.getString("MainGUI.ShotItem.text")); // NOI18N
        ShotItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ShotItemActionPerformed(evt);
            }
        });
        VideoOpt.add(ShotItem);

        RotateItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F6, 0));
        RotateItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/RotateIcon.png"))); // NOI18N
        RotateItem.setText(bundle.getString("MainGUI.RotateItem.text")); // NOI18N
        RotateItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                RotateItemActionPerformed(evt);
            }
        });
        VideoOpt.add(RotateItem);

        OptionsMenu.add(VideoOpt);

        AudioOpt.setText(bundle.getString("MainGUI.AudioOpt.text")); // NOI18N

        MuteOpt.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M, java.awt.event.InputEvent.SHIFT_MASK));
        MuteOpt.setSelected(Settings.getInstance().readBool("emu.mutesound"));
        MuteOpt.setText(bundle.getString("MainGUI.MuteOpt.text")); // NOI18N
        MuteOpt.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                MuteOptActionPerformed(evt);
            }
        });
        AudioOpt.add(MuteOpt);

        OptionsMenu.add(AudioOpt);

        ClockSpeedOpt.setText(bundle.getString("MainGUI.ClockSpeedOpt.text")); // NOI18N

        clockSpeedGroup.add(ClockSpeed50);
        ClockSpeed50.setText(bundle.getString("MainGUI.ClockSpeed50.text")); // NOI18N
        ClockSpeed50.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClockSpeed50ActionPerformed(evt);
            }
        });
        ClockSpeedOpt.add(ClockSpeed50);

        clockSpeedGroup.add(ClockSpeed75);
        ClockSpeed75.setText(bundle.getString("MainGUI.ClockSpeed75.text")); // NOI18N
        ClockSpeed75.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClockSpeed75ActionPerformed(evt);
            }
        });
        ClockSpeedOpt.add(ClockSpeed75);

        clockSpeedGroup.add(ClockSpeedNormal);
        ClockSpeedNormal.setSelected(true);
        ClockSpeedNormal.setText(bundle.getString("MainGUI.ClockSpeedNormal.text")); // NOI18N
        ClockSpeedNormal.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClockSpeedNormalActionPerformed(evt);
            }
        });
        ClockSpeedOpt.add(ClockSpeedNormal);

        clockSpeedGroup.add(ClockSpeed150);
        ClockSpeed150.setText(bundle.getString("MainGUI.ClockSpeed150.text")); // NOI18N
        ClockSpeed150.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClockSpeed150ActionPerformed(evt);
            }
        });
        ClockSpeedOpt.add(ClockSpeed150);

        clockSpeedGroup.add(ClockSpeed200);
        ClockSpeed200.setText(bundle.getString("MainGUI.ClockSpeed200.text")); // NOI18N
        ClockSpeed200.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClockSpeed200ActionPerformed(evt);
            }
        });
        ClockSpeedOpt.add(ClockSpeed200);

        clockSpeedGroup.add(ClockSpeed300);
        ClockSpeed300.setText(bundle.getString("MainGUI.ClockSpeed300.text")); // NOI18N
        ClockSpeed300.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClockSpeed300ActionPerformed(evt);
            }
        });
        ClockSpeedOpt.add(ClockSpeed300);

        OptionsMenu.add(ClockSpeedOpt);

        ControlsConf.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F11, 0));
        ControlsConf.setText(bundle.getString("MainGUI.ControlsConf.text")); // NOI18N
        ControlsConf.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ControlsConfActionPerformed(evt);
            }
        });
        OptionsMenu.add(ControlsConf);

        ConfigMenu.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F12, 0));
        ConfigMenu.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/SettingsIcon.png"))); // NOI18N
        ConfigMenu.setText(bundle.getString("MainGUI.ConfigMenu.text")); // NOI18N
        ConfigMenu.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ConfigMenuActionPerformed(evt);
            }
        });
        OptionsMenu.add(ConfigMenu);

        MenuBar.add(OptionsMenu);

        DebugMenu.setText(bundle.getString("MainGUI.DebugMenu.text")); // NOI18N

        ToolsSubMenu.setText(bundle.getString("MainGUI.ToolsSubMenu.text")); // NOI18N

        LoggerMenu.setText(bundle.getString("ConsoleWindow.title")); // NOI18N

        ToggleLogger.setText(bundle.getString("MainGUI.ToggleLogger.text")); // NOI18N
        ToggleLogger.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ToggleLoggerActionPerformed(evt);
            }
        });
        LoggerMenu.add(ToggleLogger);

        CustomLogger.setText(bundle.getString("MainGUI.CustomLogger.text")); // NOI18N
        CustomLogger.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                CustomLoggerActionPerformed(evt);
            }
        });
        LoggerMenu.add(CustomLogger);

        ToolsSubMenu.add(LoggerMenu);

        EnterDebugger.setText(bundle.getString("DisassemblerFrame.title")); // NOI18N
        EnterDebugger.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                EnterDebuggerActionPerformed(evt);
            }
        });
        ToolsSubMenu.add(EnterDebugger);

        EnterMemoryViewer.setText(bundle.getString("MemoryViewer.title")); // NOI18N
        EnterMemoryViewer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                EnterMemoryViewerActionPerformed(evt);
            }
        });
        ToolsSubMenu.add(EnterMemoryViewer);

        EnterImageViewer.setText(bundle.getString("ImageViewer.title")); // NOI18N
        EnterImageViewer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                EnterImageViewerActionPerformed(evt);
            }
        });
        ToolsSubMenu.add(EnterImageViewer);

        VfpuRegisters.setText(bundle.getString("VfpuFrame.title")); // NOI18N
        VfpuRegisters.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                VfpuRegistersActionPerformed(evt);
            }
        });
        ToolsSubMenu.add(VfpuRegisters);

        ElfHeaderViewer.setText(bundle.getString("ElfHeaderInfo.title")); // NOI18N
        ElfHeaderViewer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ElfHeaderViewerActionPerformed(evt);
            }
        });
        ToolsSubMenu.add(ElfHeaderViewer);

        FileLog.setText(bundle.getString("FileLoggerFrame.title")); // NOI18N
        FileLog.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                FileLogActionPerformed(evt);
            }
        });
        ToolsSubMenu.add(FileLog);

        InstructionCounter.setText(bundle.getString("InstructionCounter.title")); // NOI18N
        InstructionCounter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                InstructionCounterActionPerformed(evt);
            }
        });
        ToolsSubMenu.add(InstructionCounter);

        DebugMenu.add(ToolsSubMenu);

        DumpIso.setText(bundle.getString("MainGUI.DumpIso.text")); // NOI18N
        DumpIso.setEnabled(false);
        DumpIso.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                DumpIsoActionPerformed(evt);
            }
        });
        DebugMenu.add(DumpIso);

        ResetProfiler.setText(bundle.getString("MainGUI.ResetProfiler.text")); // NOI18N
        ResetProfiler.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ResetProfilerActionPerformed(evt);
            }
        });
        DebugMenu.add(ResetProfiler);

        ClearTextureCache.setText(bundle.getString("MainGUI.ClearTextureCache.text")); // NOI18N
        ClearTextureCache.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClearTextureCacheActionPerformed(evt);
            }
        });
        DebugMenu.add(ClearTextureCache);

        ClearVertexCache.setText(bundle.getString("MainGUI.ClearVertexCache.text")); // NOI18N
        ClearVertexCache.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ClearVertexCacheActionPerformed(evt);
            }
        });
        DebugMenu.add(ClearVertexCache);

        ExportISOFile.setText(bundle.getString("MainGUI.ExportISOFile.text")); // NOI18N
        ExportISOFile.setEnabled(false);
        ExportISOFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ExportISOFileActionPerformed(evt);
            }
        });
        DebugMenu.add(ExportISOFile);

        MenuBar.add(DebugMenu);

        CheatsMenu.setText(bundle.getString("MainGUI.CheatsMenu.text")); // NOI18N

        cwcheat.setText("CWCheat"); // NOI18N
        cwcheat.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cwcheatActionPerformed(evt);
            }
        });
        CheatsMenu.add(cwcheat);

        MenuBar.add(CheatsMenu);

        LanguageMenu.setText("Language"); // NOI18N

        SystemLocale.setText(bundle.getString("MainGUI.SystemLocale.text")); // NOI18N
        SystemLocale.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                SystemLocaleActionPerformed(evt);
            }
        });
        LanguageMenu.add(SystemLocale);

        EnglishGB.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/en_UK.png"))); // NOI18N
        java.util.ResourceBundle bundle1 = java.util.ResourceBundle.getBundle("jpcsp/languages/common"); // NOI18N
        EnglishGB.setText(bundle1.getString("englishUK")); // NOI18N
        EnglishGB.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                EnglishGBActionPerformed(evt);
            }
        });
        LanguageMenu.add(EnglishGB);

        EnglishUS.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/en_US.png"))); // NOI18N
        EnglishUS.setText(bundle1.getString("englishUS")); // NOI18N
        EnglishUS.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                EnglishUSActionPerformed(evt);
            }
        });
        LanguageMenu.add(EnglishUS);

        French.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/fr_FR.png"))); // NOI18N
        French.setText(bundle1.getString("french")); // NOI18N
        French.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                FrenchActionPerformed(evt);
            }
        });
        LanguageMenu.add(French);

        German.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/de_DE.png"))); // NOI18N
        German.setText(bundle1.getString("german")); // NOI18N
        German.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                GermanActionPerformed(evt);
            }
        });
        LanguageMenu.add(German);

        Lithuanian.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/lt_LT.png"))); // NOI18N
        Lithuanian.setText(bundle1.getString("lithuanian")); // NOI18N
        Lithuanian.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                LithuanianActionPerformed(evt);
            }
        });
        LanguageMenu.add(Lithuanian);

        Spanish.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/es_ES.png"))); // NOI18N
        Spanish.setText(bundle1.getString("spanish")); // NOI18N
        Spanish.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                SpanishActionPerformed(evt);
            }
        });
        LanguageMenu.add(Spanish);

        Catalan.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/es_CA.png"))); // NOI18N
        Catalan.setText(bundle1.getString("catalan")); // NOI18N
        Catalan.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                CatalanActionPerformed(evt);
            }
        });
        LanguageMenu.add(Catalan);

        Portuguese.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/pt_PT.png"))); // NOI18N
        Portuguese.setText(bundle1.getString("portuguese")); // NOI18N
        Portuguese.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                PortugueseActionPerformed(evt);
            }
        });
        LanguageMenu.add(Portuguese);

        PortugueseBR.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/pt_BR.png"))); // NOI18N
        PortugueseBR.setText(bundle1.getString("portuguesebr")); // NOI18N
        PortugueseBR.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                PortugueseBRActionPerformed(evt);
            }
        });
        LanguageMenu.add(PortugueseBR);

        Japanese.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/jp_JP.png"))); // NOI18N
        Japanese.setText(bundle1.getString("japanese")); // NOI18N
        Japanese.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                JapaneseActionPerformed(evt);
            }
        });
        LanguageMenu.add(Japanese);

        Russian.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/ru_RU.png"))); // NOI18N
        Russian.setText(bundle1.getString("russian")); // NOI18N
        Russian.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                RussianActionPerformed(evt);
            }
        });
        LanguageMenu.add(Russian);

        Polish.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/pl_PL.png"))); // NOI18N
        Polish.setText(bundle1.getString("polish")); // NOI18N
        Polish.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                PolishActionPerformed(evt);
            }
        });
        LanguageMenu.add(Polish);

        ChinesePRC.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/cn_CN.png"))); // NOI18N
        ChinesePRC.setText(bundle1.getString("simplifiedChinese")); // NOI18N
        ChinesePRC.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ChinesePRCActionPerformed(evt);
            }
        });
        LanguageMenu.add(ChinesePRC);

        ChineseTW.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/tw_TW.png"))); // NOI18N
        ChineseTW.setText(bundle1.getString("traditionalChinese")); // NOI18N
        ChineseTW.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ChineseTWActionPerformed(evt);
            }
        });
        LanguageMenu.add(ChineseTW);

        Italian.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/it_IT.png"))); // NOI18N
        Italian.setText(bundle1.getString("italian")); // NOI18N
        Italian.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ItalianActionPerformed(evt);
            }
        });
        LanguageMenu.add(Italian);

        Greek.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/flags/gr_EL.png"))); // NOI18N
        Greek.setText(bundle1.getString("greek")); // NOI18N
        Greek.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                GreekActionPerformed(evt);
            }
        });
        LanguageMenu.add(Greek);

        MenuBar.add(LanguageMenu);

        PluginsMenu.setText(bundle.getString("MainGUI.PluginsMenu.text")); // NOI18N

        xbrzCheck.setSelected(Settings.getInstance().readBool("emu.plugins.xbrz"));
        xbrzCheck.setText(bundle.getString("MainGUI.xbrzCheck.text")); // NOI18N
        xbrzCheck.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                xbrzCheckActionPerformed(evt);
            }
        });
        PluginsMenu.add(xbrzCheck);

        MenuBar.add(PluginsMenu);

        HelpMenu.setText(bundle.getString("MainGUI.HelpMenu.text")); // NOI18N

        About.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0));
        About.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/AboutIcon.png"))); // NOI18N
        About.setText(bundle.getString("MainGUI.About.text")); // NOI18N
        About.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                AboutActionPerformed(evt);
            }
        });
        HelpMenu.add(About);

        MenuBar.add(HelpMenu);

        setJMenuBar(MenuBar);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void createComponents() {
        initComponents();

        if (useFullscreen) {
            // Hide the menu bar and the toolbar in full screen mode
            MenuBar.setVisible(false);
            mainToolBar.setVisible(false);
            getContentPane().remove(mainToolBar);

            fillerLeft = new JLabel();
            fillerRight = new JLabel();
            fillerTop = new JLabel();
            fillerBottom = new JLabel();

            fillerLeft.setBackground(Color.BLACK);
            fillerRight.setBackground(Color.BLACK);
            fillerTop.setBackground(Color.BLACK);
            fillerBottom.setBackground(Color.BLACK);

            fillerLeft.setOpaque(true);
            fillerRight.setOpaque(true);
            fillerTop.setOpaque(true);
            fillerBottom.setOpaque(true);

            getContentPane().add(fillerLeft, BorderLayout.LINE_START);
            getContentPane().add(fillerRight, BorderLayout.LINE_END);
            getContentPane().add(fillerTop, BorderLayout.NORTH);
            getContentPane().add(fillerBottom, BorderLayout.SOUTH);

            makeFullScreenMenu();
        } else {
            float viewportResizeScaleFactor = getViewportResizeScaleFactor();
            if (viewportResizeScaleFactor <= 1.5f) {
                oneTimeResize.setSelected(true);
            } else if (viewportResizeScaleFactor <= 2.5f) {
                twoTimesResize.setSelected(true);
            } else {
                threeTimesResize.setSelected(true);
            }
        }

        populateRecentMenu();
    }

    private void changeLanguage(String language) {
        Settings.getInstance().writeString("emu.language", language);
        JpcspDialogManager.showInformation(this, "Language change will take effect after application restart.");
    }

    /**
     * Create a popup menu for use in full screen mode. In full screen mode, the
     * menu bar and the toolbar are not displayed. To keep a consistent user
     * interface, the popup menu is composed of the entries from the toolbar and
     * from the menu bar.
     *
     * Accelerators do not work natively as the popup menu must have focus for
     * them to work. Therefore the accelerators are copied and handled in the
     * KeyListener related code of MainGUI for fullscreen mode.
     */
    private void makeFullScreenMenu() {
        fullScreenMenu = new JPopupMenu();

        JMenuItem popupMenuItemRun = new JMenuItem(java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp").getString("MainGUI.RunButton.text"));
        popupMenuItemRun.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/PlayIcon.png")));
        popupMenuItemRun.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                RunButtonActionPerformed(e);
            }
        });

        JMenuItem popupMenuItemPause = new JMenuItem(java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp").getString("MainGUI.PauseButton.text"));
        popupMenuItemPause.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/PauseIcon.png")));
        popupMenuItemPause.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                PauseButtonActionPerformed(e);
            }
        });

        JMenuItem popupMenuItemReset = new JMenuItem(java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp").getString("MainGUI.ResetButton.text"));
        popupMenuItemReset.setIcon(new javax.swing.ImageIcon(getClass().getResource("/jpcsp/icons/StopIcon.png")));
        popupMenuItemReset.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ResetButtonActionPerformed(e);
            }
        });

        fullScreenMenu.add(popupMenuItemRun);
        fullScreenMenu.add(popupMenuItemPause);
        fullScreenMenu.add(popupMenuItemReset);
        fullScreenMenu.addSeparator();

        // add all the menu entries from the MenuBar to the full screen menu
        while (MenuBar.getMenuCount() > 0) {
            fullScreenMenu.add(MenuBar.getMenu(0));
        }

        // move the 'Exit' menu item from the 'File' menu
        // to the end of the full screen menu for convenience
        fullScreenMenu.addSeparator();
        fullScreenMenu.add(ExitEmu);

        // the 'Resize' menu is not relevant in full screen mode
        VideoOpt.remove(ResizeMenu);

        // copy accelerators to actionListenerMap to have them handled in
        // MainGUI using the keyPressed event
        globalAccelFromMenu(fullScreenMenu);
    }

    /**
     * Add accelerators to the global action map of MainGUI.
     *
     * This function will traverse a MenuElement tree to find all JMenuItems and
     * to add accelerators if available for the given menu item.
     *
     * The element tree is traversed in a recursive manner.
     *
     * @param me The root menu element to start.
     */
    private void globalAccelFromMenu(MenuElement me) {
        for (MenuElement element : me.getSubElements()) {
            // check for JMenu before JMenuItem, as JMenuItem is derived from JMenu
            if ((element instanceof JPopupMenu) || (element instanceof JMenu)) {
                // recursively do the same for underlying menus
                globalAccelFromMenu(element);
            } else if (element instanceof JMenuItem) {
                JMenuItem item = (JMenuItem) element;
                // only check if the accelerator exists (i.e. is not null)
                // if no ActionListeners exist, an empty array is returned
                if (item.getAccelerator() != null) {
                    actionListenerMap.put(item.getAccelerator(), item.getActionListeners());
                }
            }
        }
    }

    public static Dimension getFullScreenDimension() {
        DisplayMode displayMode;
        if (Emulator.getMainGUI().getDisplayMode() != null) {
            displayMode = Emulator.getMainGUI().getDisplayMode();
        } else {
            displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
        }
        return new Dimension(displayMode.getWidth(), displayMode.getHeight());
//    	return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().getSize();
    }

    @Override
    public void startWindowDialog(Window window) {
        GraphicsDevice localDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (localDevice.getFullScreenWindow() != null) {
            localDevice.setFullScreenWindow(null);
        }
        window.setVisible(true);
    }

    @Override
    public void startBackgroundWindowDialog(Window window) {
        startWindowDialog(window);
        requestFocus();
        Modules.sceDisplayModule.getCanvas().requestFocusInWindow();
    }

    @Override
    public void endWindowDialog() {
        if (displayMode != null) {
            GraphicsDevice localDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (localDevice.getFullScreenWindow() == null) {
                localDevice.setFullScreenWindow(this);
                setDisplayMode();
            }
            if (useFullscreen) {
                setFullScreenDisplaySize();
            }
        }
    }

    private void changeScreenResolution(int width, int height) {
        // Find the matching display mode with the preferred refresh rate
        // (or the highest refresh rate if the preferred refresh rate is not found).
        GraphicsDevice localDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        DisplayMode[] displayModes = localDevice.getDisplayModes();
        DisplayMode bestDisplayMode = null;
        for (int i = 0; displayModes != null && i < displayModes.length; i++) {
            DisplayMode dispMode = displayModes[i];
            if (dispMode.getWidth() == width && dispMode.getHeight() == height && dispMode.getBitDepth() == displayModeBitDepth) {
                if (bestDisplayMode == null || (bestDisplayMode.getRefreshRate() < dispMode.getRefreshRate() && bestDisplayMode.getRefreshRate() != preferredDisplayModeRefreshRate)) {
                    bestDisplayMode = dispMode;
                }
            }
        }

        if (bestDisplayMode != null) {
            changeScreenResolution(bestDisplayMode);
        }
    }

    private void setDisplayMode() {
        if (displayMode != null) {
            GraphicsDevice localDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            localDevice.setDisplayMode(displayMode);

            if (setLocationThread == null) {
                // Set up a thread calling setLocation() at regular intervals.
                // It seems that the window location is sometimes lost when
                // changing the DisplayMode.
                setLocationThread = new SetLocationThread();
                setLocationThread.setName("Set MainGUI Location Thread");
                setLocationThread.setDaemon(true);
                setLocationThread.start();
            }
        }
    }

    @Override
    public void setLocation() {
        if (displayMode != null && useFullscreen) {
            // FIXME When running in non-native resolution, the window is not displaying
            // if it is completely visible. It is only displaying if part of it is
            // hidden (e.g. outside screen borders).
            // This seems to be a Java bug.
            // Hack here is to move the window 1 pixel outside the screen so that
            // it gets displayed.
            if (fillerTop == null || fillerTop.getHeight() == 0) {
                if (getLocation().y != -1) {
                    setLocation(0, -1);
                }
            } else if (fillerLeft.getWidth() == 0) {
                if (getLocation().x != -1) {
                    setLocation(-1, 0);
                }
            }
        }
    }

    @Override
    public void setFullScreenDisplaySize() {
        Dimension size = new Dimension(getResizedWidth(Screen.width), getResizedHeight(Screen.height));
        setFullScreenDisplaySize(size);
    }

    private void setFullScreenDisplaySize(Dimension size) {
        Dimension fullScreenSize = getFullScreenDimension();

        setLocation();
        if (size.width < fullScreenSize.width) {
            fillerLeft.setSize((fullScreenSize.width - size.width) / 2, fullScreenSize.height);
            fillerRight.setSize(fullScreenSize.width - size.width - fillerLeft.getWidth(), fullScreenSize.height);
        } else {
            fillerLeft.setSize(0, 0);
            fillerRight.setSize(1, fullScreenSize.height);
            setSize(fullScreenSize.width + 1, fullScreenSize.height);
            setPreferredSize(getSize());
        }

        if (size.height < fullScreenSize.height) {
            fillerTop.setSize(fullScreenSize.width, (fullScreenSize.height - size.height) / 2);
            fillerBottom.setSize(fullScreenSize.width, fullScreenSize.height - size.height - fillerTop.getHeight());
        } else {
            fillerTop.setSize(0, 0);
            fillerBottom.setSize(fullScreenSize.width, 1);
            setSize(fullScreenSize.width, fullScreenSize.height + 1);
            setPreferredSize(getSize());
        }

        fillerLeft.setPreferredSize(fillerLeft.getSize());
        fillerRight.setPreferredSize(fillerRight.getSize());
        fillerTop.setPreferredSize(fillerTop.getSize());
        fillerBottom.setPreferredSize(fillerBottom.getSize());
    }

    private void changeScreenResolution(DisplayMode displayMode) {
        GraphicsDevice localDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (localDevice.isFullScreenSupported()) {
            this.displayMode = displayMode;
            localDevice.setFullScreenWindow(this);
            setDisplayMode();
            if (useFullscreen) {
                setSize(getFullScreenDimension());
                setPreferredSize(getFullScreenDimension());
                setLocation();
            }

            if (log.isInfoEnabled()) {
                log.info(String.format("Changing resolution to %dx%d, %d bits, %d Hz", displayMode.getWidth(), displayMode.getHeight(), displayMode.getBitDepth(), displayMode.getRefreshRate()));
            }
        }
    }

    public LogWindow getConsoleWindow() {
        return State.logWindow;
    }

    private void populateRecentMenu() {
        RecentMenu.removeAll();
        recentUMD.clear();
        recentFile.clear();

        Settings.getInstance().readRecent("umd", recentUMD);
        Settings.getInstance().readRecent("file", recentFile);

        for (RecentElement umd : recentUMD) {
            JMenuItem item = new JMenuItem(umd.toString());
            item.addActionListener(new RecentElementActionListener(this, RecentElementActionListener.TYPE_UMD, umd.path));
            RecentMenu.add(item);
        }

        if (!recentUMD.isEmpty() && !recentFile.isEmpty()) {
            RecentMenu.addSeparator();
        }

        for (RecentElement file : recentFile) {
            JMenuItem item = new JMenuItem(file.toString());
            item.addActionListener(new RecentElementActionListener(this, RecentElementActionListener.TYPE_FILE, file.path));
            RecentMenu.add(item);
        }
    }

private void EnterDebuggerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_EnterDebuggerActionPerformed
        if (State.debugger == null) {
            State.debugger = new DisassemblerFrame(emulator);

            // When opening the debugger, recompile all code blocks as debugger-specific code needs to be generated
            RuntimeContext.invalidateAll();
        } else {
            State.debugger.RefreshDebugger(false);
        }
        startWindowDialog(State.debugger);
}//GEN-LAST:event_EnterDebuggerActionPerformed

private void RunButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_RunButtonActionPerformed
		run();
}//GEN-LAST:event_RunButtonActionPerformed

    private JFileChooser makeJFileChooser() {
        final JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp").getString("MainGUI.strOpenELFPBP.text"));
        fc.setCurrentDirectory(new java.io.File("."));
        return fc;
    }
private void OpenFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_OpenFileActionPerformed
        PauseEmu();

        final JFileChooser fc = makeJFileChooser();
        String lastOpenedFolder = Settings.getInstance().readString("gui.lastOpenedFileFolder");
        if (lastOpenedFolder != null) {
            fc.setCurrentDirectory(new File(lastOpenedFolder));
        }
        int returnVal = fc.showOpenDialog(this);

        if (userChooseSomething(returnVal)) {
            Settings.getInstance().writeString("gui.lastOpenedFileFolder", fc.getSelectedFile().getParent());
            File file = fc.getSelectedFile();
            switch (FileUtil.getExtension(file)) {
                case "iso":
                case "cso":
                    loadUMD(file);
                    break;
                default:
                    loadFile(file);
                    break;
            }
        }
}//GEN-LAST:event_OpenFileActionPerformed

    private String pspifyFilename(String pcfilename) {
        // Files relative to ms0 directory
        if (pcfilename.startsWith("ms0")) {
            return "ms0:" + pcfilename.substring(3).replaceAll("\\\\", "/").toUpperCase();
        }
        // Files relative to flash0 directory
        if (pcfilename.startsWith("flash0")) {
            return "flash0:" + pcfilename.substring(6).replaceAll("\\\\", "/");
        }

        // Files with absolute path but also in ms0 directory
        try {
            String ms0path = new File("ms0").getCanonicalPath();
            if (pcfilename.startsWith(ms0path)) {
                // Strip off absolute prefix
                return "ms0:" + pcfilename.substring(ms0path.length()).replaceAll("\\\\", "/");
            }
        } catch (Exception e) {
            // Required by File.getCanonicalPath
            e.printStackTrace();
        }

        // Files anywhere on user's hard drive, may not work
        // use host0:/ ?
        return pcfilename.replaceAll("\\\\", "/");
    }

    public void loadFile(File file) {
    	loadFile(file, false);
    }

    public void loadFile(File file, boolean isInternal) {
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp"); // NOI18N

        //This is where a real application would open the file.
        try {
            if (State.logWindow != null) {
                State.logWindow.clearScreenMessages();
            }
            log.info(MetaInformation.FULL_NAME);

            umdLoaded = false;
            loadedFile = file;

            // Create a read-only memory-mapped file
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            ByteBuffer readbuffer;
            FileChannel roChannel = null;
            long size = raf.length();
            // Do not try to map very large files, this would raise on OutOfMemory exception.
            if (size > 1 * 1024 * 1024) {
            	readbuffer = Utilities.readAsByteBuffer(raf);
            } else {
                roChannel = raf.getChannel();
                readbuffer = roChannel.map(FileChannel.MapMode.READ_ONLY, 0, (int) roChannel.size());
            }
            SceModule module = emulator.load(pspifyFilename(file.getPath()), readbuffer);
            if (roChannel != null) {
                roChannel.close();
            }
            raf.close();

            boolean isHomebrew;
            if (isInternal) {
            	isHomebrew = false;
            } else {
	            PSF psf = module.psf;
	            String title;
	            String discId = State.DISCID_UNKNOWN_FILE;
	            if (psf != null) {
	                title = psf.getPrintableString("TITLE");
	                discId = psf.getString("DISC_ID");
	                if (discId == null) {
	                    discId = State.DISCID_UNKNOWN_FILE;
	                }
	                isHomebrew = psf.isLikelyHomebrew();
	            } else {
	                title = file.getParentFile().getName();
	                isHomebrew = true; // missing psf, assume homebrew
	            }
	            setTitle(MetaInformation.FULL_NAME + " - " + title);
	            addRecentFile(file, title);

	            RuntimeContext.setIsHomebrew(isHomebrew);
	            State.discId = discId;
	            State.title = title;
            }

            // Strip off absolute file path if the file is inside our ms0 directory
            String filepath = file.getParent();
            String ms0path = new File("ms0").getCanonicalPath();
            if (filepath.startsWith(ms0path)) {
                filepath = filepath.substring(ms0path.length() - 3); // path must start with "ms0"
            }

            Modules.IoFileMgrForUserModule.setfilepath(filepath);
            Modules.IoFileMgrForUserModule.setIsoReader(null);
            jpcsp.HLE.Modules.sceUmdUserModule.setIsoReader(null);

            if (!isHomebrew && !isInternal) {
                Settings.getInstance().loadPatchSettings();
            }
            if (!isRunningFromVsh() && !isRunningReboot()) {
            	logStart();
            }

            if (instructioncounter != null) {
                instructioncounter.RefreshWindow();
            }
            StepLogger.clear();
            StepLogger.setName(file.getPath());
        } catch (GeneralJpcspException e) {
            JpcspDialogManager.showError(this, bundle.getString("MainGUI.strGeneralError.text") + ": " + e.getLocalizedMessage());
        } catch (IOException e) {
            if (file.getName().contains("iso") || file.getName().contains("cso")) {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + ": " + bundle.getString("MainGUI.strWrongLoader.text"));
            } else {
                e.printStackTrace();
                JpcspDialogManager.showError(this, bundle.getString("ioError") + ": " + e.getLocalizedMessage());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            if (ex.getMessage() != null) {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + ": " + ex.getLocalizedMessage());
            } else {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + ": " + bundle.getString("MainGUI.strCheckConsole.text"));
            }
        }
        RefreshUI();
    }

    private void addRecentFile(File file, String title) {
        String s = file.getPath();
        for (int i = 0; i < recentFile.size(); ++i) {
            if (recentFile.get(i).path.equals(s)) {
                recentFile.remove(i--);
            }
        }
        recentFile.add(0, new RecentElement(s, title));
        while (recentFile.size() > MAX_RECENT) {
            recentFile.remove(MAX_RECENT);
        }
        Settings.getInstance().writeRecent("file", recentFile);
        populateRecentMenu();
    }

    private void removeRecentFile(String file) {
        // use Iterator to safely remove elements while traversing
        Iterator<RecentElement> it = recentFile.iterator();
        while (it.hasNext()) {
            RecentElement re = it.next();
            if (re.path.equals(file)) {
                it.remove();
            }
        }
        Settings.getInstance().writeRecent("file", recentFile);
        populateRecentMenu();
    }

    private void addRecentUMD(File file, String title) {
    	if (file == null) {
    		return;
    	}

    	try {
            String s = file.getCanonicalPath();
            for (int i = 0; i < recentUMD.size(); ++i) {
                if (recentUMD.get(i).path.equals(s)) {
                    recentUMD.remove(i--);
                }
            }
            recentUMD.add(0, new RecentElement(s, title));
            while (recentUMD.size() > MAX_RECENT) {
                recentUMD.remove(MAX_RECENT);
            }
            Settings.getInstance().writeRecent("umd", recentUMD);
            populateRecentMenu();
        } catch (IOException e) {
        	log.error("addRecentUMD", e);
        }
    }

    private void moveUpRecentUMD(File file) {
    	String title = null;
		try {
			String path = file.getCanonicalPath();
	    	for (RecentElement re : recentUMD) {
	    		if (re.path.equals(path)) {
	    			title = re.title;
	    			break;
	    		}
	    	}
		} catch (IOException e) {
        	log.error("moveUpRecentUMD", e);
		}

    	if (title != null) {
    		addRecentUMD(file, title);
    	}
    }

    private void moveUpRecentFile(File file) {
    	String title = null;
		try {
			String path = file.getCanonicalPath();
	    	for (RecentElement re : recentFile) {
	    		if (re.path.equals(path)) {
	    			title = re.title;
	    			break;
	    		}
	    	}
		} catch (IOException e) {
        	log.error("moveUpRecentFile", e);
		}

    	if (title != null) {
    		addRecentFile(file, title);
    	}
    }

    private void removeRecentUMD(String file) {
        // use Iterator to safely remove elements while traversing
        Iterator<RecentElement> it = recentUMD.iterator();
        while (it.hasNext()) {
            RecentElement re = it.next();
            if (re.path.equals(file)) {
                it.remove();
            }
        }
        Settings.getInstance().writeRecent("umd", recentUMD);
        populateRecentMenu();
    }

private void PauseButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_PauseButtonActionPerformed
		pause();
}//GEN-LAST:event_PauseButtonActionPerformed

private void ElfHeaderViewerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ElfHeaderViewerActionPerformed
        if (State.elfHeader == null) {
            State.elfHeader = new ElfHeaderInfo();
        } else {
            State.elfHeader.RefreshWindow();
        }
        startWindowDialog(State.elfHeader);
}//GEN-LAST:event_ElfHeaderViewerActionPerformed

private void EnterMemoryViewerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_EnterMemoryViewerActionPerformed
        if (State.memoryViewer == null) {
            State.memoryViewer = new MemoryViewer();
        } else {
            State.memoryViewer.RefreshMemory();
        }
        startWindowDialog(State.memoryViewer);
}//GEN-LAST:event_EnterMemoryViewerActionPerformed

private void EnterImageViewerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_EnterImageViewerActionPerformed
        if (State.imageViewer == null) {
            State.imageViewer = new ImageViewer();
        } else {
            State.imageViewer.RefreshImage();
        }
        startWindowDialog(State.imageViewer);
}//GEN-LAST:event_EnterImageViewerActionPerformed

private void AboutActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_AboutActionPerformed
        StringBuilder message = new StringBuilder();
        message
                .append("<html>")
                .append("<h2>")
                .append(MetaInformation.FULL_NAME)
                .append("</h2>")
                .append("<hr/>")
                .append("Official site      : <a href='")
                .append(MetaInformation.OFFICIAL_SITE)
                .append("'>")
                .append(MetaInformation.OFFICIAL_SITE)
                .append("</a><br/>")
                .append("Official forum     : <a href='")
                .append(MetaInformation.OFFICIAL_FORUM)
                .append("'>")
                .append(MetaInformation.OFFICIAL_FORUM)
                .append("</a><br/>")
                .append("Official repository: <a href='")
                .append(MetaInformation.OFFICIAL_REPOSITORY)
                .append("'>")
                .append(MetaInformation.OFFICIAL_REPOSITORY)
                .append("</a><br/>")
                .append("<hr/>")
                .append("<i>Team:</i> <font color='gray'>")
                .append(MetaInformation.TEAM)
                .append("</font>")
                .append("</html>");
        JOptionPane.showMessageDialog(this, message.toString(), MetaInformation.FULL_NAME, JOptionPane.INFORMATION_MESSAGE);
}//GEN-LAST:event_AboutActionPerformed

private void ConfigMenuActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ConfigMenuActionPerformed
        if (State.settingsGUI == null) {
            State.settingsGUI = new SettingsGUI();
        } else {
            State.settingsGUI.RefreshWindow();
        }
        startWindowDialog(State.settingsGUI);
}//GEN-LAST:event_ConfigMenuActionPerformed

private void ExitEmuActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ExitEmuActionPerformed
        exitEmu();
}//GEN-LAST:event_ExitEmuActionPerformed

private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        // this is only needed for the main screen, as it can be closed without
        // being deactivated first
        WindowPropSaver.saveWindowProperties(this);
        exitEmu();
}//GEN-LAST:event_formWindowClosing

private void openUmdActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openUmdActionPerformed
		if (!isRunningFromVsh()) {
			PauseEmu();
		}

        if (Settings.getInstance().readBool("emu.umdbrowser")) {
            umdbrowser = new UmdBrowser(this, getUmdPaths(false));
            umdbrowser.setVisible(true);
        } else {
            final JFileChooser fc = makeJFileChooser();
            String lastOpenedFolder = Settings.getInstance().readString("gui.lastOpenedUmdFolder");
            if (lastOpenedFolder != null) {
                fc.setCurrentDirectory(new File(lastOpenedFolder));

            }
            fc.setDialogTitle(java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp").getString("MainGUI.strOpenUMD.text"));
            int returnVal = fc.showOpenDialog(this);

            if (userChooseSomething(returnVal)) {
                Settings.getInstance().writeString("gui.lastOpenedUmdFolder", fc.getSelectedFile().getParent());
                File file = fc.getSelectedFile();
                loadAndRunUMD(file);
            }
        }
}//GEN-LAST:event_openUmdActionPerformed

private void switchUmdActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_switchUmdActionPerformed
        if (Settings.getInstance().readBool("emu.umdbrowser")) {
            umdbrowser = new UmdBrowser(this, getUmdPaths(false));
            umdbrowser.setSwitchingUmd(true);
            umdbrowser.setVisible(true);
        } else {
            final JFileChooser fc = makeJFileChooser();
            String lastOpenedFolder = Settings.getInstance().readString("gui.lastOpenedUmdFolder");
            if (lastOpenedFolder != null) {
                fc.setCurrentDirectory(new File(lastOpenedFolder));
            }
            fc.setDialogTitle(java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp").getString("MainGUI.switchUMD.text"));
            int returnVal = fc.showOpenDialog(this);

            if (userChooseSomething(returnVal)) {
                Settings.getInstance().writeString("gui.lastOpenedUmdFolder", fc.getSelectedFile().getParent());
                File file = fc.getSelectedFile();
                switchUMD(file);
            }
        }
}//GEN-LAST:event_switchUmdActionPerformed

private void ejectMsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ejectMsActionPerformed
	if (MemoryStick.isInserted()) {
		Modules.IoFileMgrForUserModule.hleEjectMemoryStick();
	} else {
		Modules.IoFileMgrForUserModule.hleInsertMemoryStick();
	}
}//GEN-LAST:event_ejectMsActionPerformed

	@Override
	public void onMemoryStickChange() {
        ResourceBundle bundle = ResourceBundle.getBundle("jpcsp/languages/jpcsp");
		if (MemoryStick.isInserted()) {
	        ejectMs.setText(bundle.getString("MainGUI.ejectMs.text"));
		} else {
	        ejectMs.setText(bundle.getString("MainGUI.insertMs.text"));
		}
	}

	public void RefreshUI() {
        ExportISOFile.setEnabled(umdLoaded);
        DumpIso.setEnabled(umdLoaded);
    }

    /**
     * Don't call this directly, see loadUMD(File file)
     */
    private boolean loadUMD(UmdIsoReader iso, String bootPath) throws IOException {
        boolean success = false;
        try {
            UmdIsoFile bootBin = iso.getFile(bootPath);
            if (bootBin.length() != 0) {
                byte[] bootfile = new byte[(int) bootBin.length()];
                bootBin.read(bootfile);
                ByteBuffer buf = ByteBuffer.wrap(bootfile);
                emulator.load("disc0:/" + bootPath, buf);
                success = true;
            }
        } catch (FileNotFoundException e) {
            System.out.println(e.getMessage());
        } catch (GeneralJpcspException e) {
        }
        return success;
    }

    /**
     * Don't call this directly, see loadUMD(File file)
     */
    private boolean loadUnpackedUMD(String filename) throws IOException, GeneralJpcspException {
    	if (doUmdBuffering) {
    		return false;
    	}

    	// Load unpacked BOOT.BIN as if it came from the umd
        File file = new File(filename);
        if (file.exists()) {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            FileChannel roChannel = raf.getChannel();
            ByteBuffer readbuffer = roChannel.map(FileChannel.MapMode.READ_ONLY, 0, (int) roChannel.size());
            emulator.load("disc0:/PSP_GAME/SYSDIR/EBOOT.BIN", readbuffer);
            raf.close();
            log.info("Using unpacked UMD EBOOT.BIN image");
            return true;
        }
        return false;
    }

    public void loadAndRunUMD(File file) {
		loadUMD(file);

		if (!isRunningFromVsh() && !isRunningReboot()) {
    		loadAndRun();
    	}
    }

    public void loadUMD(File file) {
    	String filePath = file == null ? null : file.getPath();
        UmdIsoReader.setDoIsoBuffering(doUmdBuffering);

        UmdIsoReader iso = null;
        boolean closeIso = false;
		try {
			iso = new UmdIsoReader(filePath);
			logStartIso(iso);
			if (isRunningReboot()) {
				MMIOHandlerUmd.getInstance().switchUmd(filePath);
			} else if (isRunningFromVsh()) {
	            Modules.sceUmdUserModule.hleUmdSwitch(iso);
			} else {
				closeIso = true;
		        if (iso.hasFile("PSP_GAME/param.sfo")) {
		        	loadUMDGame(file);
		        } else if (iso.hasFile("UMD_VIDEO/param.sfo")) {
		        	loadUMDVideo(file);
		        } else if (iso.hasFile("UMD_AUDIO/param.sfo")) {
		        	loadUMDAudio(file);
		        } else {
		        	// Does the EBOOT.PBP contain an ELF file?
		        	byte[] pspData = iso.readPspData();
		        	if (pspData != null) {
		        		loadFile(file);
		        	}
		        }
			}
		} catch (IOException e) {
			log.error("loadUMD", e);
			closeIso = true;
		} finally {
			if (closeIso) {
				try {
					if (iso != null) {
						iso.close();
					}
				} catch (IOException e) {
					log.error("loadUMD", e);
				}
			}
		}
        RefreshUI();
    }

    public void switchUMD(File file) {
        try {
            UmdIsoReader iso = new UmdIsoReader(file.getPath());
            if (!iso.hasFile("PSP_GAME/param.sfo")) {
            	log.error(String.format("The UMD '%s' is not a PSP_GAME UMD", file));
            	return;
            }

            log.info(String.format("Switching to the UMD %s", file));

            Modules.sceUmdUserModule.hleUmdSwitch(iso);
        } catch (IOException e) {
        	log.error("switchUMD", e);
        }
    }

    public void loadUMDGame(File file) {
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp"); // NOI18N
        String filePath = file == null ? null : file.getPath();
        try {
            if (State.logWindow != null) {
                State.logWindow.clearScreenMessages();
            }
            logStart();

            Modules.SysMemUserForUserModule.reset();
            log.info(MetaInformation.FULL_NAME);

            umdLoaded = true;
            loadedFile = file;

            UmdIsoReader iso = new UmdIsoReader(filePath);

            // Dump unpacked PBP
            if (iso.isPBP() && Settings.getInstance().readBool("emu.pbpunpack")) {
            	PBP.unpackPBP(new LocalVirtualFile(new SeekableRandomFile(filePath, "r")));
            }

            UmdIsoFile psfFile = iso.getFile("PSP_GAME/param.sfo");

            PSF psf = new PSF();
            byte[] data = new byte[(int) psfFile.length()];
            psfFile.read(data);
            psf.read(ByteBuffer.wrap(data));

            String title = psf.getPrintableString("TITLE");
            String discId = psf.getString("DISC_ID");
            String titleFormat = "%s - %s";
            if (discId == null) {
                discId = State.DISCID_UNKNOWN_UMD;
            } else {
            	titleFormat += " [%s]";
            }
            setTitle(String.format(titleFormat, MetaInformation.FULL_NAME, title, discId));

            addRecentUMD(file, title);

            if (psf.isLikelyHomebrew()) {
                emulator.setFirmwareVersion(Loader.FIRMWAREVERSION_HOMEBREW);
            } else {
                emulator.setFirmwareVersion(psf.getString("PSP_SYSTEM_VER"));
            }
            RuntimeContext.setIsHomebrew(psf.isLikelyHomebrew());

            State.discId = discId;

            State.umdId = null;
            try {
                UmdIsoFile umdDataBin = iso.getFile("UMD_DATA.BIN");
                if (umdDataBin != null) {
                    byte[] buffer = new byte[(int) umdDataBin.length()];
                    umdDataBin.readFully(buffer);
                    umdDataBin.close();
                    String umdDataBinContent = new String(buffer).replace((char) 0, ' ');

                    String[] parts = umdDataBinContent.split("\\|");
                    if (parts != null && parts.length >= 2) {
                        State.umdId = parts[1];
                    }
                }
            } catch (FileNotFoundException e) {
                // Ignore exception
            }

            Settings.getInstance().loadPatchSettings();

            // Set the memory model 32MB/64MB before loading the EBOOT.BIN
            int memorySize = Settings.getInstance().readInt("memorySize", 0);
            if (memorySize > 0) {
                log.info(String.format("Using memory size 0x%X from settings for %s", memorySize, State.discId));
                Modules.SysMemUserForUserModule.setMemorySize(memorySize);
            } else {
                boolean hasMemory64MB = psf.getNumeric("MEMSIZE") == 1;
                if (Settings.getInstance().readBool("memory64MB")) {
                    log.info(String.format("Using 64MB memory from settings for %s", State.discId));
                    hasMemory64MB = true;
                }
                Modules.SysMemUserForUserModule.setMemory64MB(hasMemory64MB);
            }

            if ((!discId.equals(State.DISCID_UNKNOWN_UMD) && loadUnpackedUMD(discId + ".BIN"))
                    || // Try to load a previously decrypted EBOOT.BIN (faster)
                    (!discId.equals(State.DISCID_UNKNOWN_UMD) && loadUnpackedUMD(Settings.getInstance().getDiscTmpDirectory() + "EBOOT.BIN"))
                    || // Try to load the EBOOT.BIN (before the BOOT.BIN, same games have an invalid BOOT.BIN but a valid EBOOT.BIN)
                    loadUMD(iso, "PSP_GAME/SYSDIR/EBOOT.OLD")
                    || loadUMD(iso, "PSP_GAME/SYSDIR/EBOOT.BIN")
                    || // As the last chance, try to load the BOOT.BIN
                    loadUMD(iso, "PSP_GAME/SYSDIR/BOOT.BIN")) {

                State.title = title;

                Modules.IoFileMgrForUserModule.setfilepath("disc0/");

                Modules.IoFileMgrForUserModule.setIsoReader(iso);
                Modules.sceUmdUserModule.setIsoReader(iso);

                if (instructioncounter != null) {
                    instructioncounter.RefreshWindow();
                }
                StepLogger.clear();
                if (filePath != null) {
                	StepLogger.setName(filePath);
                }
            } else {
                State.discId = State.DISCID_UNKNOWN_NOTHING_LOADED;
                throw new GeneralJpcspException(bundle.getString("MainGUI.strEncryptedBoot.text"));
            }
        } catch (GeneralJpcspException e) {
            JpcspDialogManager.showError(this, e.getLocalizedMessage());
        } catch (IOException e) {
            e.printStackTrace();
            JpcspDialogManager.showError(this, bundle.getString("MainGUI.strIOError.text") + " : " + e.getLocalizedMessage());
        } catch (Exception ex) {
            ex.printStackTrace();
            if (ex.getMessage() != null) {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + " : " + ex.getLocalizedMessage());
            } else {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + " : " + bundle.getString("MainGUI.strCheckConsole.text"));
            }
        }
    }

    public void loadUMDVideo(File file) {
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp"); // NOI18N
        String filePath = file == null ? null : file.getPath();
        try {
            if (State.logWindow != null) {
                State.logWindow.clearScreenMessages();
            }
            logStart();
            Modules.SysMemUserForUserModule.reset();
            log.info(MetaInformation.FULL_NAME);

            umdLoaded = true;
            loadedFile = file;

            UmdIsoReader iso = new UmdIsoReader(filePath);
            UmdIsoFile psfFile = iso.getFile("UMD_VIDEO/param.sfo");
            UmdIsoFile umdDataFile = iso.getFile("UMD_DATA.BIN");

            PSF psf = new PSF();
            byte[] data = new byte[(int) psfFile.length()];
            psfFile.read(data);
            psf.read(ByteBuffer.wrap(data));

            String title = psf.getPrintableString("TITLE");
            String discId = psf.getString("DISC_ID");
            if (discId == null) {
                byte[] umdDataId = new byte[10];
                String umdDataIdString;
                umdDataFile.readFully(umdDataId, 0, 9);
                umdDataIdString = new String(umdDataId);
                if (umdDataIdString.equals("")) {
                    discId = State.DISCID_UNKNOWN_UMD;
                } else {
                    discId = umdDataIdString;
                }
            }

            setTitle(MetaInformation.FULL_NAME + " - " + title);
            addRecentUMD(file, title);

            emulator.setFirmwareVersion(psf.getString("PSP_SYSTEM_VER"));
            RuntimeContext.setIsHomebrew(false);
            Modules.SysMemUserForUserModule.setMemory64MB(psf.getNumeric("MEMSIZE") == 1);

            State.discId = discId;
            State.title = title;

            umdvideoplayer = new UmdVideoPlayer(this, iso);

            Modules.IoFileMgrForUserModule.setfilepath("disc0/");
            Modules.IoFileMgrForUserModule.setIsoReader(iso);
        } catch (Exception ex) {
            ex.printStackTrace();
            if (ex.getMessage() != null) {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + ": " + ex.getLocalizedMessage());
            } else {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + ": " + bundle.getString("MainGUI.strCheckConsole.text"));
            }
        }
    }

    public void loadUMDAudio(File file) {
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp"); // NOI18N
        try {
            if (State.logWindow != null) {
                State.logWindow.clearScreenMessages();
            }
            logStart();
            Modules.SysMemUserForUserModule.reset();
            log.info(MetaInformation.FULL_NAME);

            umdLoaded = true;
            loadedFile = file;

            UmdIsoReader iso = new UmdIsoReader(file.getPath());
            UmdIsoFile psfFile = iso.getFile("UMD_AUDIO/param.sfo");

            PSF psf = new PSF();
            byte[] data = new byte[(int) psfFile.length()];
            psfFile.read(data);
            psf.read(ByteBuffer.wrap(data));

            String title = psf.getPrintableString("TITLE");
            String discId = psf.getString("DISC_ID");
            if (discId == null) {
                discId = State.DISCID_UNKNOWN_UMD;
            }

            setTitle(MetaInformation.FULL_NAME + " - " + title);
            addRecentUMD(file, title);

            emulator.setFirmwareVersion(psf.getString("PSP_SYSTEM_VER"));
            RuntimeContext.setIsHomebrew(false);
            Modules.SysMemUserForUserModule.setMemory64MB(psf.getNumeric("MEMSIZE") == 1);

            State.discId = discId;
            State.title = title;
        } catch (IllegalArgumentException iae) {
            // Ignore...
        } catch (Exception ex) {
            ex.printStackTrace();
            if (ex.getMessage() != null) {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + ": " + ex.getLocalizedMessage());
            } else {
                JpcspDialogManager.showError(this, bundle.getString("MainGUI.strCriticalError.text") + ": " + bundle.getString("MainGUI.strCheckConsole.text"));
            }
        }
    }

    private void logConfigurationSetting(String resourceKey, String settingKey, String value, boolean textLeft, boolean square) {
        boolean isSettingFromPatch = settingKey == null ? false : Settings.getInstance().isOptionFromPatch(settingKey);
        String format;
        if (isSettingFromPatch) {
            format = textLeft ? logConfigurationSettingLeftPatch : logConfigurationSettingRightPatch;
        } else {
            format = textLeft ? logConfigurationSettingLeft : logConfigurationSettingRight;
        }

        String text = resourceKey;
        try {
            java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp", new Locale("en"));
            text = bundle.getString(resourceKey);
        } catch (MissingResourceException mre) {
            // do nothing
        }
        log.info(String.format(format, text, value, square ? '[' : '(', square ? ']' : ')', "from patch file"));
    }

    private void logConfigurationSettingBool(String resourceKey, boolean value, boolean textLeft, boolean square) {
        logConfigurationSetting(resourceKey, null, value ? "X" : " ", textLeft, square);
    }

    private void logConfigurationSettingBool(String resourceKey, String settingKey, boolean textLeft, boolean square) {
        boolean value = Settings.getInstance().readBool(settingKey);
        logConfigurationSetting(resourceKey, settingKey, value ? "X" : " ", textLeft, square);
    }

    private void logConfigurationSettingInt(String resourceKey, String settingKey, boolean textLeft, boolean square) {
        int value = Settings.getInstance().readInt(settingKey);
        logConfigurationSetting(resourceKey, settingKey, Integer.toString(value), textLeft, square);
    }

    private void logConfigurationSettingString(String resourceKey, String settingKey, boolean textLeft, boolean square) {
        String value = Settings.getInstance().readString(settingKey);
        logConfigurationSetting(resourceKey, settingKey, value, textLeft, square);
    }

    private void logConfigurationSettingList(String resourceKey, String settingKey, String[] values, boolean textLeft, boolean square) {
        int valueIndex = Settings.getInstance().readInt(settingKey);
        String value = Integer.toString(valueIndex);
        if (values != null && valueIndex >= 0 && valueIndex < values.length) {
            value = values[valueIndex];
        }
        logConfigurationSetting(resourceKey, settingKey, value, textLeft, square);
    }

    private void logConfigurationPanel(String resourceKey) {
        // jog here only in the English locale
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp", new Locale("en"));
        log.info(String.format("%s / %s", bundle.getString("SettingsGUI.title"), bundle.getString(resourceKey)));
    }

    private void logDirectory(File dir, String prefix) {
    	if (dir == null || !dir.exists()) {
    		return;
    	}

    	if (dir.isDirectory()) {
        	log.info(String.format("%s%s:", prefix, dir.getName()));
    		File[] files = dir.listFiles();
    		if (files != null) {
    			for (File file: files) {
    				logDirectory(file, prefix + "    ");
    			}
    		}
    	} else {
        	log.info(String.format("%s%s, size=0x%X", prefix, dir.getName(), dir.length()));
    	}
    }

    private void logDirectory(String dirName) {
    	File dir = new File(dirName);
    	if (dir.exists()) {
        	log.info(String.format("Contents of '%s' directory:", dirName));
        	logDirectory(dir, "  ");
    	} else {
    		log.info(String.format("Non existing directory '%s'", dirName));
    	}
    }

    private void logStart() {
        log.info(String.format("Java version: %s (%s) on %s", System.getProperty("java.version"), System.getProperty("java.runtime.version"), System.getProperty("os.name")));
        log.info(String.format("Java library path: %s", System.getProperty("java.library.path")));

        logConfigurationSettings();

        logDirectory(Settings.getInstance().getDirectoryMapping("flash0"));
    }

    private void logStartIso(UmdIsoReader iso) {
    	if (!log.isInfoEnabled()) {
    		return;
    	}

    	String[] paramSfoFiles = new String[] {
        		"PSP_GAME/param.sfo",
        		"UMD_VIDEO/param.sfo",
        		"UMD_AUDIO/param.sfo"
        };
        for (String paramSfoFile : paramSfoFiles) {
        	if (iso.hasFile(paramSfoFile)) {
        		try {
	    			UmdIsoFile psfFile = iso.getFile(paramSfoFile);
	    	        PSF psf = new PSF();
	    	        byte[] data = new byte[(int) psfFile.length()];
	    	        psfFile.read(data);
	    	        psf.read(ByteBuffer.wrap(data));

	    	        log.info(String.format("Content of %s:%s%s", paramSfoFile, System.lineSeparator(), psf));
        		} catch (IOException e) {
        			// Ignore exception
        		}
        	}
        }

        try {
            UmdIsoFile umdDataBin = iso.getFile("UMD_DATA.BIN");
            if (umdDataBin != null) {
                byte[] buffer = new byte[(int) umdDataBin.length()];
                umdDataBin.readFully(buffer);
                umdDataBin.close();
                String umdDataBinContent = new String(buffer).replace((char) 0, ' ');
                log.info(String.format("Content of UMD_DATA.BIN: '%s'", umdDataBinContent));
            }
        } catch (FileNotFoundException e) {
            // Ignore exception
		} catch (IOException e) {
            // Ignore exception
        }
    }

    private void logConfigurationSettings() {
        if (!log.isInfoEnabled()) {
            return;
        }

        log.info("Using the following settings:");

        // Log the configuration settings
        logConfigurationPanel("SettingsGUI.GeneralPanel.title");
        logConfigurationSettingList("SettingsGUI.modelLabel.text", "emu.model", SettingsGUI.getModelNames(), true, true);
        logConfigurationPanel("SettingsGUI.RegionPanel.title");
        logConfigurationSettingList("SettingsGUI.languageLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_LANGUAGE, SettingsGUI.getImposeLanguages(), true, true);
        logConfigurationSettingList("SettingsGUI.buttonLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_BUTTON_PREFERENCE, SettingsGUI.getImposeButtons(), true, true);
        logConfigurationSettingList("SettingsGUI.daylightLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_DAYLIGHT_SAVING_TIME, SettingsGUI.getSysparamDaylightSavings(), true, true);
        logConfigurationSettingList("SettingsGUI.timeFormatLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_TIME_FORMAT, SettingsGUI.getSysparamTimeFormats(), true, true);
        logConfigurationSettingList("SettingsGUI.dateFormatLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_DATE_FORMAT, SettingsGUI.getSysparamDateFormats(), true, true);
        logConfigurationSettingList("SettingsGUI.wlanPowerLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_WLAN_POWER_SAVE, SettingsGUI.getSysparamWlanPowerSaves(), true, true);
        logConfigurationSettingList("SettingsGUI.adhocChannel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_ADHOC_CHANNEL, SettingsGUI.getSysparamAdhocChannels(), true, true);
        logConfigurationSettingInt("SettingsGUI.timezoneLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_TIME_ZONE, true, true);
        logConfigurationSettingString("SettingsGUI.nicknameLabel.text", sceUtility.SYSTEMPARAM_SETTINGS_OPTION_NICKNAME, true, true);

        logConfigurationPanel("SettingsGUI.VideoPanel.title");
        logConfigurationSettingBool("SettingsGUI.useOpenglRenderer.text", !Settings.getInstance().readBool("emu.useSoftwareRenderer") && !Settings.getInstance().readBool("emu.useExternalSoftwareRenderer"), false, false);
        logConfigurationSettingBool("SettingsGUI.useSoftwareRenderer.text", "emu.useSoftwareRenderer", false, false);
        logConfigurationSettingBool("SettingsGUI.useExternalSoftwareRenderer.text", "emu.useExternalSoftwareRenderer", false, false);
        logConfigurationSettingBool("SettingsGUI.disableVBOCheck.text", "emu.disablevbo", false, true);
        logConfigurationSettingBool("SettingsGUI.hideEffectsCheck.text", "emu.hideEffects", false, true);
        logConfigurationSettingBool("SettingsGUI.useVertexCache.text", "emu.useVertexCache", false, true);
        logConfigurationSettingBool("SettingsGUI.shaderCheck.text", "emu.useshaders", false, true);
        logConfigurationSettingBool("SettingsGUI.geometryShaderCheck.text", "emu.useGeometryShader", false, true);
        logConfigurationSettingBool("SettingsGUI.disableUBOCheck.text", "emu.disableubo", false, true);
        logConfigurationSettingBool("SettingsGUI.enableVAOCheck.text", "emu.enablevao", false, true);
        logConfigurationSettingBool("SettingsGUI.enableGETextureCheck.text", "emu.enablegetexture", false, true);
        logConfigurationSettingBool("SettingsGUI.enableNativeCLUTCheck.text", "emu.enablenativeclut", false, true);
        logConfigurationSettingBool("SettingsGUI.enableDynamicShadersCheck.text", "emu.enabledynamicshaders", false, true);
        logConfigurationSettingBool("SettingsGUI.enableShaderStencilTestCheck.text", "emu.enableshaderstenciltest", false, true);
        logConfigurationSettingBool("SettingsGUI.enableShaderColorMaskCheck.text", "emu.enableshadercolormask", false, true);
        logConfigurationSettingBool("SettingsGUI.disableOptimizedVertexInfoReading.text", "emu.disableoptimizedvertexinforeading", false, true);
        logConfigurationSettingBool("SettingsGUI.saveStencilToMemory.text", "emu.saveStencilToMemory", false, true);

        logConfigurationPanel("SettingsGUI.MemoryPanel.title");
        logConfigurationSettingBool("SettingsGUI.invalidMemoryCheck.text", "emu.ignoreInvalidMemoryAccess", false, true);
        logConfigurationSettingBool("SettingsGUI.ignoreUnmappedImports.text", "emu.ignoreUnmappedImports", false, true);
        logConfigurationSettingBool("SettingsGUI.useDebugMemory.text", "emu.useDebuggerMemory", false, true);

        logConfigurationPanel("SettingsGUI.CompilerPanel.title");
        logConfigurationSettingBool("SettingsGUI.useCompiler.text", "emu.compiler", false, true);
        logConfigurationSettingBool("SettingsGUI.profileCheck.text", "emu.profiler", false, true);
        logConfigurationSettingInt("SettingsGUI.methodMaxInstructionsLabel.text", "emu.compiler.methodMaxInstructions", false, true);
        logConfigurationSettingBool("SettingsGUI.accurateVfpuDotCheck.text", "emu.accurateVfpuDot", false, true);

        logConfigurationPanel("SettingsGUI.DisplayPanel.title");
        logConfigurationSettingString("SettingsGUI.antiAliasLabel.text", "emu.graphics.antialias", true, true);
        logConfigurationSettingString("SettingsGUI.resolutionLabel.text", "emu.graphics.resolution", true, true);
        logConfigurationSettingBool("SettingsGUI.fullscreenCheck.text", "gui.fullscreen", false, true);

        logConfigurationPanel("SettingsGUI.MiscPanel.title");
        logConfigurationSettingBool("SettingsGUI.useDebugFont.text", "emu.useDebugFont", false, true);

        logConfigurationPanel("SettingsGUI.CryptoPanel.title");
        logConfigurationSettingBool("SettingsGUI.cryptoSavedata.text", "emu.cryptoSavedata", false, true);
        logConfigurationSettingBool("SettingsGUI.extractSavedataKey.text", "emu.extractSavedataKey", false, true);
        logConfigurationSettingBool("SettingsGUI.extractPGD.text", "emu.extractPGD", false, true);
        logConfigurationSettingBool("SettingsGUI.extractEboot.text", "emu.extractEboot", false, true);
        logConfigurationSettingBool("SettingsGUI.disableDLC.text", "emu.disableDLC", false, true);

        logConfigurationPanel("SettingsGUI.networkPanel.TabConstraints.tabTitle");
        logConfigurationSettingBool("SettingsGUI.lanMultiPlayerRadioButton.text", "emu.lanMultiPlayer", false, false);
        logConfigurationSettingBool("SettingsGUI.enableProOnlineRadioButton.text", "emu.enableProOnline", false, false);
        logConfigurationSettingBool("SettingsGUI.XlinkaiSupportRadioButton.text", "emu.enableXLinkKai", false, false);
        logConfigurationSettingString("SettingsGUI.metaServerLabel.text", "network.ProOnline.metaServer", true, true);
        logConfigurationSettingString("SettingsGUI.XLinkKaiServerLabel.text", "network.XLinkKai.server", true, true);
        logConfigurationSettingString("SettingsGUI.broadcastAddressLabel.text", "network.broadcastAddress", true, true);
        logConfigurationSettingString("SettingsGUI.primaryDNSLabel.text", "network.primaryDNS", true, true);
        logConfigurationSettingBool("SettingsGUI.enableChat.text", "network.enableChat", false, true);
    }

    public void loadAndRun() {
        if (Settings.getInstance().readBool("emu.loadAndRun")) {
            RunEmu();
        }
    }

    private void ResetButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ResetButtonActionPerformed
    	reset();
}//GEN-LAST:event_ResetButtonActionPerformed

    private void resetEmu() {
        if (loadedFile != null) {
            PauseEmu();
            RuntimeContext.reset();
            HLEModuleManager.getInstance().stopModules();
            if (umdLoaded) {
                loadUMD(loadedFile);
            } else {
                loadFile(loadedFile);
            }
        }
    }
private void InstructionCounterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_InstructionCounterActionPerformed
        PauseEmu();
        if (State.instructionCounter == null) {
            State.instructionCounter = new InstructionCounter();
            emulator.setInstructionCounter(State.instructionCounter);
        } else {
            State.instructionCounter.RefreshWindow();
        }
        startWindowDialog(State.instructionCounter);
}//GEN-LAST:event_InstructionCounterActionPerformed

private void FileLogActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_FileLogActionPerformed
        if (State.fileLogger == null) {
            State.fileLogger = new FileLoggerFrame();
        }
        startWindowDialog(State.fileLogger);
}//GEN-LAST:event_FileLogActionPerformed

private void VfpuRegistersActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_VfpuRegistersActionPerformed
        startWindowDialog(VfpuFrame.getInstance());
}//GEN-LAST:event_VfpuRegistersActionPerformed

private void DumpIsoActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_DumpIsoActionPerformed
        if (umdLoaded) {
            UmdIsoReader iso = Modules.IoFileMgrForUserModule.getIsoReader();
            if (iso != null) {
                try {
                    iso.dumpIndexFile("iso-index.txt");
                } catch (IOException e) {
                    // Ignore Exception
                }
            }
        }
}//GEN-LAST:event_DumpIsoActionPerformed

private void ResetProfilerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ResetProfilerActionPerformed
        Profiler.reset();
        GEProfiler.reset();
}//GEN-LAST:event_ResetProfilerActionPerformed

private void ClearTextureCacheActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClearTextureCacheActionPerformed
        VideoEngine.getInstance().clearTextureCache();
}//GEN-LAST:event_ClearTextureCacheActionPerformed

private void ClearVertexCacheActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClearVertexCacheActionPerformed
        VideoEngine.getInstance().clearVertexCache();
}//GEN-LAST:event_ClearVertexCacheActionPerformed

private void ExportISOFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ExportISOFileActionPerformed
        ResourceBundle bundle = ResourceBundle.getBundle("jpcsp/languages/jpcsp");
        String fileName = JOptionPane.showInputDialog(null, bundle.getString("MainGUI.ExportISOFileQuestion.text"), "disc0:/");
        if (fileName == null) {
            // Input cancelled
            return;
        }

        SeekableDataInput input = Modules.IoFileMgrForUserModule.getFile(fileName, IoFileMgrForUser.PSP_O_RDONLY);
        if (input == null) {
            // File does not exit
            JOptionPane.showMessageDialog(null, bundle.getString("MainGUI.FileDoesNotExist.text"), null, JOptionPane.ERROR_MESSAGE);
            return;
        }

        String exportFileName = fileName;
        if (exportFileName.contains("/")) {
            exportFileName = exportFileName.substring(exportFileName.lastIndexOf('/') + 1);
        }
        if (exportFileName.contains(":")) {
            exportFileName = exportFileName.substring(exportFileName.lastIndexOf(':') + 1);
        }

        try {
            OutputStream output = new FileOutputStream(exportFileName);
            byte[] buffer = new byte[10 * 1024];
            long readLength = 0;
            long totalLength = input.length();
            while (readLength < totalLength) {
                int length = (int) Math.min(totalLength - readLength, buffer.length);
                input.readFully(buffer, 0, length);
                output.write(buffer, 0, length);
                readLength += length;
            }
            output.close();
            input.close();

            log.info(String.format("Exported file '%s' to '%s'", fileName, exportFileName));
            String messageFormat = bundle.getString("MainGUI.FileExported.text");
            String message = MessageFormat.format(messageFormat, fileName, exportFileName);
            JOptionPane.showMessageDialog(null, message, null, JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            log.error(e);
        }

}//GEN-LAST:event_ExportISOFileActionPerformed

private void ShotItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ShotItemActionPerformed
        if (umdvideoplayer != null) {
            umdvideoplayer.takeScreenshot();
        }
        Modules.sceDisplayModule.takeScreenshot();
}//GEN-LAST:event_ShotItemActionPerformed

private void RotateItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_RotateItemActionPerformed
        sceDisplay screen = Modules.sceDisplayModule;
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("jpcsp/languages/jpcsp"); // NOI18N

        List<Object> options = new ArrayList<Object>();
        options.add(bundle.getString("MainGUI.strRotate90CW.text"));
        options.add(bundle.getString("MainGUI.strRotate90CCW.text"));
        options.add(bundle.getString("MainGUI.strRotate180.text"));
        options.add(bundle.getString("MainGUI.strRotateMirror.text"));
        options.add(bundle.getString("MainGUI.strRotateNormal.text"));

        int jop = JOptionPane.showOptionDialog(null, bundle.getString("MainGUI.strChooseRotation.text"), bundle.getString("MainGUI.strRotate.text"), JOptionPane.UNDEFINED_CONDITION, JOptionPane.QUESTION_MESSAGE, null, options.toArray(), options.get(4));
        if (jop != -1) {
            screen.rotate(jop);
        }
}//GEN-LAST:event_RotateItemActionPerformed

private void ExportVisibleElementsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ExportVisibleElementsActionPerformed
        State.exportGeNextFrame = true;
        State.exportGeOnlyVisibleElements = true;
}//GEN-LAST:event_ExportVisibleElementsActionPerformed

private void ExportAllElementsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ExportAllElementsActionPerformed
        State.exportGeNextFrame = true;
        State.exportGeOnlyVisibleElements = false;
}//GEN-LAST:event_ExportAllElementsActionPerformed

    private String getStateFileName() {
    	if (stateFileName != null) {
    		return stateFileName;
    	}

    	if (RuntimeContextLLE.isLLEActive()) {
    		return String.format("State.bin");
    	}
    	return String.format("State_%s.bin", State.discId);
    }

private void SaveSnapActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_SaveSnapActionPerformed
	try {
		new jpcsp.state.State().write(getStateFileName());
	} catch (IOException e) {
		e.printStackTrace();
	}
}//GEN-LAST:event_SaveSnapActionPerformed

private void LoadSnapActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_LoadSnapActionPerformed
	try {
		new jpcsp.state.State().read(getStateFileName());
	} catch (IOException e) {
		e.printStackTrace();
	}
}//GEN-LAST:event_LoadSnapActionPerformed

private void EnglishUSActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_EnglishUSActionPerformed
        changeLanguage("en_US");
}//GEN-LAST:event_EnglishUSActionPerformed

private void FrenchActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_FrenchActionPerformed
        changeLanguage("fr_FR");
}//GEN-LAST:event_FrenchActionPerformed

private void GermanActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_GermanActionPerformed
        changeLanguage("de_DE");
}//GEN-LAST:event_GermanActionPerformed

private void LithuanianActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_LithuanianActionPerformed
        changeLanguage("lt_LT");
}//GEN-LAST:event_LithuanianActionPerformed

private void SpanishActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_SpanishActionPerformed
        changeLanguage("es_ES");
}//GEN-LAST:event_SpanishActionPerformed

private void CatalanActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_CatalanActionPerformed
        changeLanguage("ca_ES");
}//GEN-LAST:event_CatalanActionPerformed

private void PortugueseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_PortugueseActionPerformed
        changeLanguage("pt_PT");
}//GEN-LAST:event_PortugueseActionPerformed

private void JapaneseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_JapaneseActionPerformed
        changeLanguage("ja_JP");
}//GEN-LAST:event_JapaneseActionPerformed

private void PortugueseBRActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_PortugueseBRActionPerformed
        changeLanguage("pt_BR");
}//GEN-LAST:event_PortugueseBRActionPerformed

private void cwcheatActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cwcheatActionPerformed
        if (State.cheatsGUI == null) {
            State.cheatsGUI = new CheatsGUI();
        }
        startWindowDialog(State.cheatsGUI);
}//GEN-LAST:event_cwcheatActionPerformed

private void RussianActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_RussianActionPerformed
        changeLanguage("ru_RU");
}//GEN-LAST:event_RussianActionPerformed

private void PolishActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_PolishActionPerformed
        changeLanguage("pl_PL");
}//GEN-LAST:event_PolishActionPerformed

private void ItalianActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ItalianActionPerformed
        changeLanguage("it_IT");
}//GEN-LAST:event_ItalianActionPerformed

private void GreekActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_GreekActionPerformed
        changeLanguage("el_GR");
}//GEN-LAST:event_GreekActionPerformed

private void ControlsConfActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ControlsConfActionPerformed
        startWindowDialog(new ControlsGUI());
}//GEN-LAST:event_ControlsConfActionPerformed

private void MuteOptActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_MuteOptActionPerformed
        Audio.setMuted(!Audio.isMuted());
        MuteOpt.setSelected(Audio.isMuted());
        Settings.getInstance().writeBool("emu.mutesound", Audio.isMuted());
}//GEN-LAST:event_MuteOptActionPerformed

private void ClockSpeedNormalActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClockSpeedNormalActionPerformed
        // Set clock speed to 1/1
        Emulator.setVariableSpeedClock(1, 1);
}//GEN-LAST:event_ClockSpeedNormalActionPerformed

private void ClockSpeed50ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClockSpeed50ActionPerformed
        // Set clock speed to 1/2
        Emulator.setVariableSpeedClock(1, 2);
}//GEN-LAST:event_ClockSpeed50ActionPerformed

private void ClockSpeed75ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClockSpeed75ActionPerformed
        // Set clock speed to 3/4
        Emulator.setVariableSpeedClock(3, 4);
}//GEN-LAST:event_ClockSpeed75ActionPerformed

private void ClockSpeed150ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClockSpeed150ActionPerformed
        // Set clock speed to 3/2
        Emulator.setVariableSpeedClock(3, 2);
}//GEN-LAST:event_ClockSpeed150ActionPerformed

private void ClockSpeed200ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClockSpeed200ActionPerformed
        // Set clock speed to 2/1
        Emulator.setVariableSpeedClock(2, 1);
}//GEN-LAST:event_ClockSpeed200ActionPerformed

private void ClockSpeed300ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ClockSpeed300ActionPerformed
        // Set clock speed to 3/1
        Emulator.setVariableSpeedClock(3, 1);
}//GEN-LAST:event_ClockSpeed300ActionPerformed

private void ToggleLoggerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ToggleLoggerActionPerformed
        if (!State.logWindow.isVisible()) {
            updateConsoleWinPosition();
        }
        State.logWindow.setVisible(!State.logWindow.isVisible());
        ToggleLogger.setSelected(State.logWindow.isVisible());
}//GEN-LAST:event_ToggleLoggerActionPerformed

private void CustomLoggerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_CustomLoggerActionPerformed
        if (State.logGUI == null) {
            State.logGUI = new LogGUI(this);
        }
        startWindowDialog(State.logGUI);
}//GEN-LAST:event_CustomLoggerActionPerformed

    private void ChinesePRCActionPerformed(java.awt.event.ActionEvent evt) {
        changeLanguage("zh_CN");
    }

private void ChineseTWActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_ChinesePRCActionPerformed
        changeLanguage("zh_TW");
}//GEN-LAST:event_ChinesePRCActionPerformed

private void noneCheckActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_noneCheckActionPerformed
        VideoEngine.getInstance().setUseTextureAnisotropicFilter(false);
        Settings.getInstance().writeBool("emu.graphics.filters.anisotropic", false);
}//GEN-LAST:event_noneCheckActionPerformed

private void anisotropicCheckActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_anisotropicCheckActionPerformed
        VideoEngine.getInstance().setUseTextureAnisotropicFilter(true);
        Settings.getInstance().writeBool("emu.graphics.filters.anisotropic", anisotropicCheck.isSelected());
}//GEN-LAST:event_anisotropicCheckActionPerformed

private void frameSkipNoneActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_frameSkipNoneActionPerformed
        Modules.sceDisplayModule.setDesiredFPS(0);
        Settings.getInstance().writeInt("emu.graphics.frameskip.desiredFPS", Modules.sceDisplayModule.getDesiredFPS());
}//GEN-LAST:event_frameSkipNoneActionPerformed

private void frameSkipFPS5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_frameSkipFPS5ActionPerformed
        Modules.sceDisplayModule.setDesiredFPS(5);
        Settings.getInstance().writeInt("emu.graphics.frameskip.desiredFPS", Modules.sceDisplayModule.getDesiredFPS());
}//GEN-LAST:event_frameSkipFPS5ActionPerformed

private void frameSkipFPS10ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_frameSkipFPS10ActionPerformed
        Modules.sceDisplayModule.setDesiredFPS(10);
        Settings.getInstance().writeInt("emu.graphics.frameskip.desiredFPS", Modules.sceDisplayModule.getDesiredFPS());
}//GEN-LAST:event_frameSkipFPS10ActionPerformed

private void frameSkipFPS15ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_frameSkipFPS15ActionPerformed
        Modules.sceDisplayModule.setDesiredFPS(15);
        Settings.getInstance().writeInt("emu.graphics.frameskip.desiredFPS", Modules.sceDisplayModule.getDesiredFPS());
}//GEN-LAST:event_frameSkipFPS15ActionPerformed

private void frameSkipFPS20ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_frameSkipFPS20ActionPerformed
        Modules.sceDisplayModule.setDesiredFPS(20);
        Settings.getInstance().writeInt("emu.graphics.frameskip.desiredFPS", Modules.sceDisplayModule.getDesiredFPS());
}//GEN-LAST:event_frameSkipFPS20ActionPerformed

private void frameSkipFPS30ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_frameSkipFPS30ActionPerformed
        Modules.sceDisplayModule.setDesiredFPS(30);
        Settings.getInstance().writeInt("emu.graphics.frameskip.desiredFPS", Modules.sceDisplayModule.getDesiredFPS());
}//GEN-LAST:event_frameSkipFPS30ActionPerformed

private void frameSkipFPS60ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_frameSkipFPS60ActionPerformed
        Modules.sceDisplayModule.setDesiredFPS(60);
        Settings.getInstance().writeInt("emu.graphics.frameskip.desiredFPS", Modules.sceDisplayModule.getDesiredFPS());
}//GEN-LAST:event_frameSkipFPS60ActionPerformed

    private void setViewportResizeScaleFactor(int viewportResizeScaleFactor) {
        Modules.sceDisplayModule.setViewportResizeScaleFactor(viewportResizeScaleFactor);
        pack();
        if (umdvideoplayer != null) {
            umdvideoplayer.pauseVideo();
            umdvideoplayer.setVideoPlayerResizeScaleFactor(this, viewportResizeScaleFactor);
            umdvideoplayer.resumeVideo();
        }
    }

private void oneTimeResizeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_oneTimeResizeActionPerformed
        setViewportResizeScaleFactor(1);
}//GEN-LAST:event_oneTimeResizeActionPerformed

private void twoTimesResizeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_twoTimesResizeActionPerformed
        setViewportResizeScaleFactor(2);
}//GEN-LAST:event_twoTimesResizeActionPerformed

private void threeTimesResizeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_threeTimesResizeActionPerformed
        setViewportResizeScaleFactor(3);
}//GEN-LAST:event_threeTimesResizeActionPerformed

    private void SystemLocaleActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_SystemLocaleActionPerformed
        changeLanguage("systemLocale");
    }//GEN-LAST:event_SystemLocaleActionPerformed

    private void formComponentResized(java.awt.event.ComponentEvent evt) {//GEN-FIRST:event_formComponentResized
        updateConsoleWinPosition();
    }//GEN-LAST:event_formComponentResized

    private void EnglishGBActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_EnglishGBActionPerformed
        changeLanguage("en_GB");
    }//GEN-LAST:event_EnglishGBActionPerformed

    private void xbrzCheckActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_xbrzCheckActionPerformed
        Settings.getInstance().writeBool("emu.plugins.xbrz", xbrzCheck.isSelected());
    }//GEN-LAST:event_xbrzCheckActionPerformed

    private void exitEmu() {
    	if (umdvideoplayer != null) {
    		umdvideoplayer.exit();
    	}
        ProOnlineNetworkAdapter.exit();
        XLinkKaiWlanAdapter.exit();
        Modules.ThreadManForUserModule.exit();
        Modules.sceDisplayModule.exit();
        Modules.IoFileMgrForUserModule.exit();
        VideoEngine.exit();
        Screen.exit();
        Emulator.exit();

        System.exit(0);
    }

    public void updateConsoleWinPosition() {
        if (Settings.getInstance().readBool("gui.snapLogwindow")) {
            Point mainwindowPos = getLocation();
            State.logWindow.setLocation(mainwindowPos.x, mainwindowPos.y + getHeight());
        }
    }

    private void RunEmu() {
        emulator.RunEmu();
        Modules.sceDisplayModule.getCanvas().requestFocusInWindow();
    }

    private void TogglePauseEmu() {
        // This is a toggle, so can pause and unpause
        if (Emulator.run) {
            if (!Emulator.pause) {
                Emulator.PauseEmuWithStatus(Emulator.EMU_STATUS_PAUSE);
            } else {
                RunEmu();
            }
        }
    }

    private void PauseEmu() {
        // This will only enter pause mode
        if (Emulator.run && !Emulator.pause) {
            Emulator.PauseEmuWithStatus(Emulator.EMU_STATUS_PAUSE);
        }
    }

    @Override
    public void RefreshButtons() {
        RunButton.setSelected(Emulator.run && !Emulator.pause);
        PauseButton.setSelected(Emulator.run && Emulator.pause);
    }

    @Override
    final public void onUmdChange() {
        // Only enable the menu entry "Switch UMD" when sceUmdReplacePermit has been called by the application.
        switchUmd.setEnabled(Modules.sceUmdUserModule.isUmdAllowReplace());
    }

    /**
     * set the FPS portion of the title
     */
    @Override
    public void setMainTitle(String message) {
        String oldtitle = getTitle();
        int sub = oldtitle.indexOf("FPS:");
        if (sub != -1) {
            String newtitle = oldtitle.substring(0, sub - 1);
            setTitle(newtitle + " " + message);
        } else {
            setTitle(oldtitle + " " + message);
        }
    }

    public static File[] getUmdPaths(boolean ignorePSPGame) {
        List<File> umdPaths = new LinkedList<File>();
        umdPaths.add(new File(Settings.getInstance().readString("emu.umdpath") + "/"));
        for (int i = 1; true; i++) {
            String umdPath = Settings.getInstance().readString(String.format("emu.umdpath.%d", i), null);
            if (umdPath == null) {
                break;
            }

            if (!ignorePSPGame || !(umdPath.equals("ms0\\PSP\\GAME") || umdPath.equals(Settings.getInstance().getDirectoryMapping("ms0") + "PSP/GAME"))) {
            	umdPaths.add(new File(umdPath + "/"));
            }
        }

        return umdPaths.toArray(new File[umdPaths.size()]);
    }

    private void printUsage() {
    	String javaLibraryPath = System.getProperty("java.library.path");
    	String startCmd = "start-windows-amd64.bat";
    	if (javaLibraryPath != null) {
    		if (javaLibraryPath.contains("windows-amd64")) {
    			startCmd = "start-windows-amd64.bat";
    		} else if (javaLibraryPath.contains("windows-x86")) {
    			startCmd = "start-windows-x86.bat";
    		} else if (javaLibraryPath.contains("linux-amd64")) {
    			startCmd = "sh start-linux-amd64.sh";
    		} else if (javaLibraryPath.contains("linux-x86")) {
    			startCmd = "sh start-linux-x86.sh";
    		}
    	}

    	final PrintStream out = System.err;
        out.println(String.format("Usage: %s [OPTIONS]", startCmd));
        out.println();
        out.println("  -d, --debugger             Open debugger at start.");
        out.println("  -f, --loadfile FILE        Load a file.");
        out.println("                             Example: ms0/PSP/GAME/pspsolitaire/EBOOT.PBP");
        out.println("  -u, --loadumd FILE         Load a UMD. Example: umdimages/cube.iso");
        out.println("  -r, --run                  Run loaded file or umd. Use with -f or -u option.");
        out.println("  -t, --tests                Run the automated tests.");
        out.println("  --netClientPortShift N     Increase Network client ports by N (when running 2 Jpcsp on the same computer)");
        out.println("  --netServerPortShift N     Increase Network server ports by N (when running 2 Jpcsp on the same computer)");
        out.println("  --flash0 DIRECTORY         Use the given directory name for the PSP flash0:  device, instead of \"flash0/\"  by default.");
		out.println("  --flash1 DIRECTORY         Use the given directory name for the PSP flash1:  device, instead of \"flash1/\"  by default.");
		out.println("  --flash2 DIRECTORY         Use the given directory name for the PSP flash2:  device, instead of \"flash2/\"  by default.");
		out.println("  --ms0 DIRECTORY            Use the given directory name for the PSP ms0:     device, instead of \"ms0/\"     by default.");
		out.println("  --exdata0 DIRECTORY        Use the given directory name for the PSP exdata0: device, instead of \"exdata0/\" by default.");
		out.println("  --logsettings FILE         Use the given file for the log4j configuration, instead of \"LogSettings.xml\" by default.");
		out.println("  --stateFileName FILE       Use the given file when saving/loading the snapshot.");
		out.println("  --settingsFileName FILE    Use the given file when saving/loading the settings (Settings.properties by default).");
		out.println("  --vsh                      Run the PSP VSH.");
		out.println("  --reboot                   Run a low-level emulation of the complete PSP reboot process. Still experimental.");
    }

    private void processArgs(String[] args) {
    	if (log.isInfoEnabled()) {
	    	log.info(String.format("Started with the following command line options:"));
	    	for (int i = 0; i < args.length; i++) {
	    		log.info(String.format("    \"%s\"", args[i]));
	    	}
    	}

    	for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-t") || args[i].equals("--tests")) {
                throw (new RuntimeException("Shouldn't get there"));
            } else if (args[i].equals("-d") || args[i].equals("--debugger")) {
                EnterDebuggerActionPerformed(null);
            } else if (args[i].equals("-f") || args[i].equals("--loadfile")) {
                i++;
                if (i < args.length) {
                    File file = new File(args[i]);
                    if (file.exists()) {
                        Modules.sceDisplayModule.setCalledFromCommandLine();
                        loadFile(file);
                    }
                } else {
                    printUsage();
                    break;
                }
            } else if (args[i].equals("-u") || args[i].equals("--loadumd")) {
                i++;
                if (i < args.length) {
                    File file = new File(args[i]);
                    if (file.exists()) {
                        Modules.sceDisplayModule.setCalledFromCommandLine();
                        loadUMD(file);
                    }
                } else {
                    printUsage();
                    break;
                }
            } else if (args[i].equals("--bufferumd")) {
            	doUmdBuffering = true;
            } else if (args[i].equals("--loadbufferedumd")) {
            	doUmdBuffering = true;
            	Modules.sceDisplayModule.setCalledFromCommandLine();
            	loadUMD(null);
            } else if (args[i].equals("-r") || args[i].equals("--run")) {
                RunEmu();
            } else if (args[i].equals("--netClientPortShift")) {
                i++;
                if (i < args.length) {
                    int netClientPortShift = Integer.parseInt(args[i]);
                    Modules.sceNetAdhocModule.setNetClientPortShift(netClientPortShift);
                } else {
                    printUsage();
                    break;
                }
            } else if (args[i].equals("--netServerPortShift")) {
                i++;
                if (i < args.length) {
                    int netServerPortShift = Integer.parseInt(args[i]);
                    Modules.sceNetAdhocModule.setNetServerPortShift(netServerPortShift);
                } else {
                    printUsage();
                    break;
                }
            } else if (args[i].equals("--ProOnline")) {
                ProOnlineNetworkAdapter.setEnabled(true);
            } else if (args[i].equals("--localIPAddress")) {
            	i++;
            	if (i < args.length) {
            		Wlan.setLocalIPAddress(args[i]);
            	} else {
            		printUsage();
            		break;
            	}
            } else if (args[i].equals("--vsh")) {
            	runFromVsh = true;
            	logStart();
	            setTitle(MetaInformation.FULL_NAME + " - VSH");
	            Emulator.getInstance().setFirmwareVersion(660);
                Modules.sceDisplayModule.setCalledFromCommandLine();
                HTTPServer.processProxyRequestLocally = true;

                if (!Modules.rebootModule.loadAndRun()) {
                    loadFile(new File(Settings.getInstance().getDirectoryMapping("flash0") + "vsh/module/vshmain.prx"), true);
                }

                Modules.IoFileMgrForUserModule.setfilepath(Settings.getInstance().getDirectoryMapping("ms0") + "PSP/GAME");

            	// Start VSH with the lowest priority so that the initialization of the other
            	// modules can be completed.
            	// The VSH root thread is running in KERNEL mode.
            	SceKernelThreadInfo rootThread = Modules.ThreadManForUserModule.getRootThread(null);
            	if (rootThread != null) {
            		rootThread.currentPriority = 0x7E;
            		rootThread.attr |= SceKernelThreadInfo.PSP_THREAD_ATTR_KERNEL;
            		rootThread.attr &= ~SceKernelThreadInfo.PSP_THREAD_ATTR_USER;
            	}

            	HLEModuleManager.getInstance().LoadFlash0Module("PSP_MODULE_AV_VAUDIO");
            	HLEModuleManager.getInstance().LoadFlash0Module("PSP_MODULE_AV_ATRAC3PLUS");
            	HLEModuleManager.getInstance().LoadFlash0Module("PSP_MODULE_AV_AVCODEC");
            } else if (args[i].equals("--reboot")) {
            	doReboot();
            } else if (args[i].equals("--debugCodeBlockCalls")) {
            	RuntimeContext.debugCodeBlockCalls = true;
            } else if (args[i].matches("--flash[0-2]") || args[i].matches("--ms[0]") || args[i].matches("--exdata[0]")) {
            	String directoryName = args[i].substring(2);
            	i++;
            	if (i < args.length) {
            		String mappedDirectoryName = args[i];
            		// The mapped directory name must end with "/"
            		if (!mappedDirectoryName.endsWith("/")) {
            			mappedDirectoryName += "/";
            		}
            		Settings.getInstance().setDirectoryMapping(directoryName, mappedDirectoryName);
            		log.info(String.format("Mapping '%s' to directory '%s'", directoryName, mappedDirectoryName));
            	} else {
            		printUsage();
            		break;
            	}
            } else if (args[i].equals("--logsettings")) {
            	// This argument has already been processed in initLog()
            	i++;
            } else if (args[i].equals("--stateFileName")) {
            	i++;
            	if (i < args.length) {
            		stateFileName = args[i];
            	} else {
            		printUsage();
            	}
            } else if (args[i].equals("--settingsFileName")) {
            	// This argument has already been processed in initSettings()
            	i++;
            } else if (args[i].equals("--debuggerMemoryFileName")) {
            	// This argument has already been processed in initSettings()
            	i++;
            } else {
                printUsage();
                break;
            }
        }
    }

    private static void initLog(String args[]) {
    	String logSettingsFileName = "LogSettings.xml";

    	// Verify if another LogSettings.xml file name has been provided on the command line
    	for (int i = 0; i < args.length; i++) {
    		if (args[i].equals("--logsettings")) {
    			i++;
    			logSettingsFileName = args[i];
    		}
    	}

    	DOMConfigurator.configure(logSettingsFileName);
        setLog4jMDC();
    }

    private static void initSettings(String args[]) {
    	// Verify if settings file name has been provided on the command line
    	for (int i = 0; i < args.length; i++) {
    		if (args[i].equals("--settingsFileName")) {
    			i++;
    			Settings.SETTINGS_FILE_NAME = args[i];
    		}
    	}
    }

    private static void initDebuggerMemory(String args[]) {
    	// Verify if the debugger memory file name has been provided on the command line
    	for (int i = 0; i < args.length; i++) {
    		if (args[i].equals("--debuggerMemoryFileName")) {
    			i++;
    			DebuggerMemory.mBrkFilePath = args[i];
    		}
    	}
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
    	initLog(args);
    	initSettings(args);
    	initDebuggerMemory(args);

		// Re-enable all disabled algorithms as the PSP is allowing them
		Security.setProperty("jdk.certpath.disabledAlgorithms", "");
		Security.setProperty("jdk.tls.disabledAlgorithms", "");

    	PreDecrypt.init();
        AES128.init();
        libkirk.AES.init();

        HTTPServer.getInstance();

        // prepare i18n
        String locale = Settings.getInstance().readString("emu.language");
        if (!locale.equals("systemLocale")) {
            // extract language and country for Locale()
            String language = locale.substring(0, 2);
            String country = locale.substring(3, 5);

            Locale.setDefault(new Locale(language, country));
            ResourceBundle.clearCache();
        }

        if (args.length > 0) {
            if (args[0].equals("--tests")) {
                (new AutoTestsRunner()).run();
                return;
            }
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // add the window property saver class to the event listeners for
        // automatic persistent saving of the window positions if needed
        Toolkit.getDefaultToolkit().addAWTEventListener(
                new WindowPropSaver(), AWTEvent.WINDOW_EVENT_MASK);

        // final copy of args for use in inner class
        final String[] fargs = args;

        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setName("GUI");
                MainGUI maingui = new MainGUI();
                maingui.setVisible(true);

                if (Settings.getInstance().readBool("gui.openLogwindow")) {
                    State.logWindow.setVisible(true);
                    maingui.ToggleLogger.setSelected(true);
                }

                maingui.processArgs(fargs);
                initAfterArgs();
            }
        });
    }

    private static void initAfterArgs() {
    	if (!Wlan.hasLocalInetAddress()) {
    		InetAddress localIPAddress = AutoDetectLocalIPAddress.getInstance().getLocalIPAddress();
    		Wlan.setLocalInetAddress(localIPAddress);
    	}
    }

    @Override
    public boolean isFullScreen() {
        return useFullscreen;
    }
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JMenuItem About;
    private javax.swing.JMenu AudioOpt;
    private javax.swing.JMenuItem Catalan;
    private javax.swing.JMenu CheatsMenu;
    private javax.swing.JMenuItem ChinesePRC;
    private javax.swing.JMenuItem ChineseTW;
    private javax.swing.JMenuItem ClearTextureCache;
    private javax.swing.JMenuItem ClearVertexCache;
    private javax.swing.JCheckBoxMenuItem ClockSpeed150;
    private javax.swing.JCheckBoxMenuItem ClockSpeed200;
    private javax.swing.JCheckBoxMenuItem ClockSpeed300;
    private javax.swing.JCheckBoxMenuItem ClockSpeed50;
    private javax.swing.JCheckBoxMenuItem ClockSpeed75;
    private javax.swing.JCheckBoxMenuItem ClockSpeedNormal;
    private javax.swing.JMenu ClockSpeedOpt;
    private javax.swing.JMenuItem ConfigMenu;
    private javax.swing.JMenuItem ControlsConf;
    private javax.swing.JMenuItem CustomLogger;
    private javax.swing.JMenu DebugMenu;
    private javax.swing.JMenuItem DumpIso;
    private javax.swing.JMenuItem ElfHeaderViewer;
    private javax.swing.JMenuItem EnglishGB;
    private javax.swing.JMenuItem EnglishUS;
    private javax.swing.JMenuItem EnterDebugger;
    private javax.swing.JMenuItem EnterImageViewer;
    private javax.swing.JMenuItem EnterMemoryViewer;
    private javax.swing.JMenuItem ExitEmu;
    private javax.swing.JMenuItem ExportAllElements;
    private javax.swing.JMenuItem ExportISOFile;
    private javax.swing.JMenu ExportMenu;
    private javax.swing.JMenuItem ExportVisibleElements;
    private javax.swing.JCheckBoxMenuItem FPS10;
    private javax.swing.JCheckBoxMenuItem FPS15;
    private javax.swing.JCheckBoxMenuItem FPS20;
    private javax.swing.JCheckBoxMenuItem FPS30;
    private javax.swing.JCheckBoxMenuItem FPS5;
    private javax.swing.JCheckBoxMenuItem FPS60;
    private javax.swing.JMenuItem FileLog;
    private javax.swing.JMenu FileMenu;
    private javax.swing.JMenu FiltersMenu;
    private javax.swing.JMenu FrameSkipMenu;
    private javax.swing.JCheckBoxMenuItem FrameSkipNone;
    private javax.swing.JMenuItem French;
    private javax.swing.JMenuItem German;
    private javax.swing.JMenuItem Greek;
    private javax.swing.JMenu HelpMenu;
    private javax.swing.JMenuItem InstructionCounter;
    private javax.swing.JMenuItem Italian;
    private javax.swing.JMenuItem Japanese;
    private javax.swing.JMenu LanguageMenu;
    private javax.swing.JMenuItem Lithuanian;
    private javax.swing.JMenuItem LoadSnap;
    private javax.swing.JMenu LoggerMenu;
    private javax.swing.JMenuBar MenuBar;
    private javax.swing.JCheckBoxMenuItem MuteOpt;
    private javax.swing.JMenuItem OpenFile;
    private javax.swing.JMenu OptionsMenu;
    private javax.swing.JToggleButton PauseButton;
    private javax.swing.JMenu PluginsMenu;
    private javax.swing.JMenuItem Polish;
    private javax.swing.JMenuItem Portuguese;
    private javax.swing.JMenuItem PortugueseBR;
    private javax.swing.JMenu RecentMenu;
    private javax.swing.JButton ResetButton;
    private javax.swing.JMenuItem ResetProfiler;
    private javax.swing.JMenu ResizeMenu;
    private javax.swing.JMenuItem RotateItem;
    private javax.swing.JToggleButton RunButton;
    private javax.swing.JMenuItem Russian;
    private javax.swing.JMenuItem SaveSnap;
    private javax.swing.JMenuItem ShotItem;
    private javax.swing.JMenuItem Spanish;
    private javax.swing.JMenuItem SystemLocale;
    private javax.swing.JCheckBoxMenuItem ToggleLogger;
    private javax.swing.JMenu ToolsSubMenu;
    private javax.swing.JMenuItem VfpuRegisters;
    private javax.swing.JMenu VideoOpt;
    private javax.swing.JCheckBoxMenuItem anisotropicCheck;
    private javax.swing.ButtonGroup clockSpeedGroup;
    private javax.swing.JMenuItem cwcheat;
    private javax.swing.ButtonGroup filtersGroup;
    private javax.swing.ButtonGroup frameSkipGroup;
    private javax.swing.JSeparator jSeparator1;
    private javax.swing.JSeparator jSeparator2;
    private javax.swing.JToolBar mainToolBar;
    private javax.swing.JCheckBoxMenuItem noneCheck;
    private javax.swing.JCheckBoxMenuItem oneTimeResize;
    private javax.swing.JMenuItem openUmd;
    private javax.swing.ButtonGroup resGroup;
    private javax.swing.JMenuItem switchUmd;
    private javax.swing.JMenuItem ejectMs;
    private javax.swing.JCheckBoxMenuItem threeTimesResize;
    private javax.swing.JCheckBoxMenuItem twoTimesResize;
    private javax.swing.JCheckBoxMenuItem xbrzCheck;
    // End of variables declaration//GEN-END:variables

    private boolean userChooseSomething(int returnVal) {
        return returnVal == JFileChooser.APPROVE_OPTION;
    }

    @Override
    public void mousePressed(MouseEvent event) {
        if (useFullscreen && event.isPopupTrigger()) {
            fullScreenMenu.show(event.getComponent(), event.getX(), event.getY());
        }
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        if (useFullscreen && event.isPopupTrigger()) {
            fullScreenMenu.show(event.getComponent(), event.getX(), event.getY());
        }
    }

    @Override
    public void mouseClicked(MouseEvent event) {
    }

    @Override
    public void mouseEntered(MouseEvent event) {
    }

    @Override
    public void mouseExited(MouseEvent event) {
    }

    @Override
    public void keyTyped(KeyEvent event) {
    }

    @Override
    public void keyPressed(KeyEvent event) {
        State.controller.keyPressed(event);

        // check if the stroke is a known accelerator and call the associated ActionListener(s)
        KeyStroke stroke = KeyStroke.getKeyStroke(event.getKeyCode(), event.getModifiers());
        if (actionListenerMap.containsKey(stroke)) {
            for (ActionListener al : actionListenerMap.get(stroke)) {
                al.actionPerformed(new ActionEvent(event.getSource(), event.getID(), ""));
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent event) {
        State.controller.keyReleased(event);
    }

    @Override
    public void componentHidden(ComponentEvent e) {
    }

    @Override
    public void componentMoved(ComponentEvent e) {
        if (State.logWindow.isVisible()) {
            updateConsoleWinPosition();
        }
    }

    @Override
    public void componentResized(ComponentEvent e) {
    }

    @Override
    public void componentShown(ComponentEvent e) {
    }

    private class RecentElementActionListener implements ActionListener {

        public static final int TYPE_UMD = 0;
        public static final int TYPE_FILE = 1;
        private int type;
        private String path;
        private Component parent;

        public RecentElementActionListener(Component parent, int type, String path) {
            this.parent = parent;
            this.path = path;
            this.type = type;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            File file = new File(path);
            if (file.exists()) {
                if (type == TYPE_UMD) {
                	moveUpRecentUMD(file);
                    loadUMD(file);
                } else {
                	moveUpRecentFile(file);
                    loadFile(file);
                }
                loadAndRun();
            } else {
                ResourceBundle bundle = ResourceBundle.getBundle("jpcsp/languages/jpcsp");
                String messageFormat = bundle.getString("MainGUI.RecentFileNotFound.text");
                String message = MessageFormat.format(messageFormat, path);
                JpcspDialogManager.showError(parent, message);
                if (type == TYPE_UMD) {
                    removeRecentUMD(path);
                } else {
                    removeRecentFile(path);
                }
            }
        }
    }

    private static class SetLocationThread extends Thread {

        @Override
        public void run() {
            while (true) {
                try {
                    // Wait for 1 second
                    sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore Exception
                }

                Emulator.getMainGUI().setLocation();
            }
        }
    }

    @Override
    public Rectangle getCaptureRectangle() {
        Insets insets = getInsets();
        Rectangle canvasBounds = Modules.sceDisplayModule.getCanvas().getBounds();
        Rectangle contentBounds = getContentPane().getBounds();

        return new Rectangle(getX() + insets.left + contentBounds.x + canvasBounds.x, getY() + insets.top + contentBounds.y + canvasBounds.y, canvasBounds.width, canvasBounds.height);
    }

	@Override
	public void run() {
        if (umdvideoplayer != null) {
            umdvideoplayer.initVideo();
        }
        RunEmu();
	}

	@Override
	public void pause() {
        if (umdvideoplayer != null) {
            umdvideoplayer.pauseVideo();
        }
        TogglePauseEmu();
	}

	@Override
	public void reset() {
        resetEmu();
	}

	@Override
	public boolean isRunningFromVsh() {
		return runFromVsh;
	}

	@Override
	public boolean isRunningReboot() {
		return reboot.enableReboot;
	}

	@Override
	public void doReboot() {
    	reboot.enableReboot = true;
    	logStart();
    	Emulator.getInstance().onReboot();
        setTitle(MetaInformation.FULL_NAME + " - reboot");
        Modules.sceDisplayModule.setCalledFromCommandLine();
        HTTPServer.processProxyRequestLocally = true;

        if (!Modules.rebootModule.loadAndRun()) {
        	log.error(String.format("Cannot reboot - missing files"));
        	reboot.enableReboot = false;
        }
	}
}
