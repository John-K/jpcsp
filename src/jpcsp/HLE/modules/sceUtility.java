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
package jpcsp.HLE.modules;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static jpcsp.Allegrex.Common._s0;
import static jpcsp.Allegrex.Common._s1;
import static jpcsp.HLE.HLEModuleManager.HLESyscallNid;
import static jpcsp.HLE.Modules.ThreadManForUserModule;
import static jpcsp.HLE.Modules.sceUtilityModule;
import static jpcsp.HLE.VFS.local.LocalVirtualFileSystem.getMsFileName;
import static jpcsp.HLE.kernel.types.SceUtilitySavedataParam.ERROR_SAVEDATA_CANCELLED;
import static jpcsp.HLE.kernel.types.SceUtilityScreenshotParams.PSP_UTILITY_SCREENSHOT_FORMAT_JPEG;
import static jpcsp.HLE.kernel.types.SceUtilityScreenshotParams.PSP_UTILITY_SCREENSHOT_FORMAT_PNG;
import static jpcsp.HLE.kernel.types.SceUtilityScreenshotParams.PSP_UTILITY_SCREENSHOT_NAMERULE_AUTONUM;
import static jpcsp.HLE.modules.SysMemUserForUser.USER_PARTITION_ID;
import static jpcsp.HLE.modules.sceFont.PSP_FONT_PIXELFORMAT_4;
import static jpcsp.HLE.modules.sceSuspendForUser.KERNEL_VOLATILE_MEM_SIZE;
import static jpcsp.HLE.modules.sceSuspendForUser.KERNEL_VOLATILE_MEM_START;
import static jpcsp.graphics.GeCommands.ALPHA_ONE_MINUS_SOURCE_ALPHA;
import static jpcsp.graphics.GeCommands.ALPHA_SOURCE_ALPHA;
import static jpcsp.graphics.GeCommands.ALPHA_SOURCE_BLEND_OPERATION_ADD;
import static jpcsp.graphics.GeCommands.CLEAR_COLOR_BUFFER;
import static jpcsp.graphics.GeCommands.CMODE_FORMAT_32BIT_ABGR8888;
import static jpcsp.graphics.GeCommands.PRIM_SPRITES;
import static jpcsp.graphics.GeCommands.TFLT_LINEAR;
import static jpcsp.graphics.GeCommands.TFUNC_FRAGMENT_DOUBLE_TEXTURE_EFECT_REPLACE;
import static jpcsp.graphics.GeCommands.TPSM_PIXEL_STORAGE_MODE_32BIT_ABGR8888;
import static jpcsp.graphics.GeCommands.TPSM_PIXEL_STORAGE_MODE_4BIT_INDEXED;
import static jpcsp.graphics.GeCommands.TWRAP_WRAP_MODE_CLAMP;
import static jpcsp.graphics.GeCommands.VTYPE_POSITION_FORMAT_16_BIT;
import static jpcsp.graphics.GeCommands.VTYPE_TEXTURE_FORMAT_16_BIT;
import static jpcsp.graphics.GeCommands.VTYPE_TRANSFORM_PIPELINE_RAW_COORD;
import static jpcsp.graphics.RE.IRenderingEngine.GU_TEXTURE_2D;
import static jpcsp.graphics.VideoEngine.alignBufferWidth;
import static jpcsp.memory.ImageReader.colorARGBtoABGR;
import jpcsp.GUI.SettingsGUI;
import jpcsp.HLE.BufferInfo;
import jpcsp.HLE.BufferInfo.LengthInfo;
import jpcsp.HLE.BufferInfo.Usage;
import jpcsp.HLE.CanBeNull;
import jpcsp.HLE.HLEFunction;
import jpcsp.HLE.HLELogging;
import jpcsp.HLE.HLEModule;
import jpcsp.HLE.HLEModuleManager;
import jpcsp.HLE.HLEUnimplemented;
import jpcsp.HLE.PspString;
import jpcsp.HLE.StringInfo;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.TPointer32;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

import jpcsp.Controller;
import jpcsp.Emulator;
import jpcsp.GeneralJpcspException;
import jpcsp.Loader;
import jpcsp.Memory;
import jpcsp.NIDMapper;
import jpcsp.Processor;
import jpcsp.State;
import jpcsp.HLE.Modules;
import jpcsp.HLE.VFS.IVirtualFile;
import jpcsp.HLE.VFS.IVirtualFileSystem;
import jpcsp.HLE.kernel.Managers;
import jpcsp.HLE.kernel.managers.SystemTimeManager;
import jpcsp.HLE.kernel.types.SceFontInfo;
import jpcsp.HLE.kernel.types.SceIoStat;
import jpcsp.HLE.kernel.types.SceKernelErrors;
import jpcsp.HLE.kernel.types.SceKernelLMOption;
import jpcsp.HLE.kernel.types.SceKernelThreadInfo;
import jpcsp.HLE.kernel.types.SceModule;
import jpcsp.HLE.kernel.types.SceUtilityGamedataInstallParams;
import jpcsp.HLE.kernel.types.SceUtilityGameSharingParams;
import jpcsp.HLE.kernel.types.SceUtilityHtmlViewerParams;
import jpcsp.HLE.kernel.types.SceUtilityInstallParams;
import jpcsp.HLE.kernel.types.SceUtilityMsgDialogParams;
import jpcsp.HLE.kernel.types.SceUtilityNetconfParams;
import jpcsp.HLE.kernel.types.SceUtilityNpSigninParams;
import jpcsp.HLE.kernel.types.SceUtilityOskParams;
import jpcsp.HLE.kernel.types.SceUtilitySavedataParam;
import jpcsp.HLE.kernel.types.SceUtilityScreenshotParams;
import jpcsp.HLE.kernel.types.pspUtilityBaseDialog;
import jpcsp.HLE.kernel.types.pspUtilityDialogCommon;
import jpcsp.HLE.kernel.types.SceUtilityOskParams.SceUtilityOskData;
import jpcsp.HLE.kernel.types.pspCharInfo;
import jpcsp.HLE.modules.ModuleMgrForUser.LoadModuleContext;
import jpcsp.HLE.modules.SysMemUserForUser.SysMemInfo;
import jpcsp.crypto.CryptoEngine;
import jpcsp.filesystems.SeekableDataInput;
import jpcsp.format.PNG;
import jpcsp.format.PSF;
import jpcsp.graphics.RE.IRenderingEngine;
import jpcsp.graphics.capture.CaptureImage;
import jpcsp.hardware.MemoryStick;
import jpcsp.hardware.Screen;
import jpcsp.memory.IMemoryReader;
import jpcsp.memory.IMemoryWriter;
import jpcsp.memory.MemoryReader;
import jpcsp.memory.MemoryWriter;
import jpcsp.settings.Settings;
import jpcsp.util.HLEUtilities;
import jpcsp.util.MemoryInputStream;
import jpcsp.util.Utilities;
import jpcsp.util.sceGu;

import org.apache.log4j.Logger;

public class sceUtility extends HLEModule {
    public static Logger log = Modules.getLogger("sceUtility");

    @Override
    public void start() {
        gameSharingState = new GameSharingUtilityDialogState("sceUtilityGameSharing");
        netplayDialogState = new NotImplementedUtilityDialogState("sceNetplayDialog");
        netconfState = new NetconfUtilityDialogState("sceUtilityNetconf");
        savedataState = new SavedataUtilityDialogState("sceUtilitySavedata");
        msgDialogState = new MsgDialogUtilityDialogState("sceUtilityMsgDialog");
        oskState = new OskUtilityDialogState("sceUtilityOsk");
        npSigninState = new NpSigninUtilityDialogState("sceUtilityNpSignin");
        PS3ScanState = new NotImplementedUtilityDialogState("sceUtilityPS3Scan");
        rssReaderState = new NotImplementedUtilityDialogState("sceUtilityRssReader");
        rssSubscriberState = new NotImplementedUtilityDialogState("sceUtilityRssSubscriber");
        screenshotState = new ScreenshotUtilityDialogState("sceUtilityScreenshot");
        htmlViewerState = new HtmlViewerUtilityDialogState("sceUtilityHtmlViewer");
        savedataErrState = new NotImplementedUtilityDialogState("sceUtilitySavedataErr");
        gamedataInstallState = new GamedataInstallUtilityDialogState("sceUtilityGamedataInstall");
		storeCheckoutState = new NotImplementedUtilityDialogState("sceUtilityStoreCheckout");
		psnState = new NotImplementedUtilityDialogState("sceUtilityPsn");
		installState = new InstallUtilityDialogState("sceUtilityInstall");
        startedDialogState = null;

        utilityPrivateModules = new HashMap<String, String>();
        utilityPrivateModules.put("htmlviewer_ui", "flash0:/vsh/module/htmlviewer_ui.prx");
        utilityPrivateModules.put("hvauth_r", "flash0:/vsh/module/hvauth_r.prx");
        utilityPrivateModules.put("hvauth_t", "flash0:/vsh/module/hvauth_t.prx");
        utilityPrivateModules.put("netfront", "flash0:/vsh/module/netfront.prx");
        utilityPrivateModules.put("mgvideo", "flash0:/kd/mgvideo.prx");
        utilityPrivateModules.put("mm_flash", "flash0:/vsh/module/mm_flash.prx");
        utilityPrivateModules.put("libslim", "flash0:/vsh/module/libslim.prx");
        utilityPrivateModules.put("libwww", "flash0:/vsh/module/libwww.prx");
        utilityPrivateModules.put("libfont_hv", "flash0:/vsh/module/libfont_hv.prx");

        UTILITY_LOOP_ADDRESS = HLEUtilities.getInstance().installLoopHandler(this, "hleUtilityThread");

        super.start();
    }

	@Override
	public void stop() {
		loadedModules.clear();
		waitingModules.clear();
		super.stop();
	}

    public static final String SYSTEMPARAM_SETTINGS_OPTION_NICKNAME = "emu.sysparam.nickname";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_ADHOC_CHANNEL = "emu.sysparam.adhocchannel";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_WLAN_POWER_SAVE = "emu.sysparam.wlanpowersave";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_DATE_FORMAT = "emu.sysparam.dateformat";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_TIME_FORMAT = "emu.sysparam.timeformat";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_TIME_ZONE = "emu.sysparam.timezone";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_DAYLIGHT_SAVING_TIME = "emu.sysparam.daylightsavings";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_LANGUAGE = "emu.impose.language";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_BUTTON_PREFERENCE = "emu.impose.button";
    public static final String SYSTEMPARAM_SETTINGS_OPTION_LOCK_PARENTAL_LEVEL = "emu.sysparam.locl.parentallevel";
    public static final int PSP_SYSTEMPARAM_ID_STRING_NICKNAME = 1; // PSP Registry "/CONFIG/SYSTEM/owner_name"
    public static final int PSP_SYSTEMPARAM_ID_INT_ADHOC_CHANNEL = 2; // PSP Registry "/CONFIG/NETWORK/ADHOC/channel"
    public static final int PSP_SYSTEMPARAM_ID_INT_WLAN_POWERSAVE = 3; // PSP Registry "/CONFIG/SYSTEM/POWER_SAVING/wlan_mode"
    public static final int PSP_SYSTEMPARAM_ID_INT_DATE_FORMAT = 4; // PSP Registry "/CONFIG/DATE/date_format"
    public static final int PSP_SYSTEMPARAM_ID_INT_TIME_FORMAT = 5; // PSP Registry "/CONFIG/DATE/time_format"
    public static final int PSP_SYSTEMPARAM_ID_INT_TIMEZONE = 6; // PSP Registry "/CONFIG/DATE/time_zone_offset"
    public static final int PSP_SYSTEMPARAM_ID_INT_DAYLIGHTSAVINGS = 7; // PSP Registry "/CONFIG/DATE/summer_time"
    public static final int PSP_SYSTEMPARAM_ID_INT_LANGUAGE = 8; // PSP Registry "/CONFIG/SYSTEM/XMB/language"
    public static final int PSP_SYSTEMPARAM_ID_INT_BUTTON_PREFERENCE = 9; // PSP Registry "/CONFIG/SYSTEM/XMB/button_assign"
    public static final int PSP_SYSTEMPARAM_ID_INT_LOCK_PARENTAL_LEVEL = 10; // PSP Registry "/CONFIG/SYSTEM/LOCK/parental_level"
    public static final int PSP_SYSTEMPARAM_LANGUAGE_JAPANESE = 0;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_ENGLISH = 1;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_FRENCH = 2;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_SPANISH = 3;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_GERMAN = 4;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_ITALIAN = 5;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_DUTCH = 6;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_PORTUGUESE = 7;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_RUSSIAN = 8;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_KOREAN = 9;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_CHINESE_TRADITIONAL = 10;
    public static final int PSP_SYSTEMPARAM_LANGUAGE_CHINESE_SIMPLIFIED = 11;
    public static final int PSP_SYSTEMPARAM_DATE_FORMAT_YYYYMMDD = 0;
    public static final int PSP_SYSTEMPARAM_DATE_FORMAT_MMDDYYYY = 1;
    public static final int PSP_SYSTEMPARAM_DATE_FORMAT_DDMMYYYY = 2;
    public static final int PSP_SYSTEMPARAM_TIME_FORMAT_24HR = 0;
    public static final int PSP_SYSTEMPARAM_TIME_FORMAT_12HR = 1;
    public final static int PSP_SYSTEMPARAM_BUTTON_CIRCLE = 0;
    public final static int PSP_SYSTEMPARAM_BUTTON_CROSS = 1;
    public static final int PSP_UTILITY_DIALOG_STATUS_NONE = 0;
    public static final int PSP_UTILITY_DIALOG_STATUS_INIT = 1;
    public static final int PSP_UTILITY_DIALOG_STATUS_VISIBLE = 2;
    public static final int PSP_UTILITY_DIALOG_STATUS_QUIT = 3;
    public static final int PSP_UTILITY_DIALOG_STATUS_FINISHED = 4;
    public static final int PSP_UTILITY_DIALOG_STATUS_SCREENSHOT_UNKNOWN = 5;
    public static final int PSP_UTILITY_DIALOG_RESULT_OK = 0;
    public static final int PSP_UTILITY_DIALOG_RESULT_CANCELED = 1;
    public static final int PSP_UTILITY_DIALOG_RESULT_ABORTED = 2;
    public static final int PSP_NETPARAM_NAME = 0; // string
    public static final int PSP_NETPARAM_SSID = 1; // string
    public static final int PSP_NETPARAM_SECURE = 2; // int
    public static final int PSP_NETPARAM_WEPKEY = 3; // string
    public static final int PSP_NETPARAM_IS_STATIC_IP = 4; // int
    public static final int PSP_NETPARAM_IP = 5; // string
    public static final int PSP_NETPARAM_NETMASK = 6; // string
    public static final int PSP_NETPARAM_ROUTE = 7; // string
    public static final int PSP_NETPARAM_MANUAL_DNS = 8; // int
    public static final int PSP_NETPARAM_PRIMARYDNS = 9; // string
    public static final int PSP_NETPARAM_SECONDARYDNS = 10; // string
    public static final int PSP_NETPARAM_PROXY_USER = 11; // string
    public static final int PSP_NETPARAM_PROXY_PASS = 12; // string
    public static final int PSP_NETPARAM_USE_PROXY = 13; // int
    public static final int PSP_NETPARAM_PROXY_SERVER = 14; // string
    public static final int PSP_NETPARAM_PROXY_PORT = 15; // int
    public static final int PSP_NETPARAM_VERSION = 16; // int
    public static final int PSP_NETPARAM_UNKNOWN = 17; // int
    public static final int PSP_NETPARAM_8021X_AUTH_TYPE = 18; // int
    public static final int PSP_NETPARAM_8021X_USER = 19; // string
    public static final int PSP_NETPARAM_8021X_PASS = 20; // string
    public static final int PSP_NETPARAM_WPA_TYPE = 21; // int
    public static final int PSP_NETPARAM_WPA_KEY = 22; // string
    public static final int PSP_NETPARAM_BROWSER = 23; // int
    public static final int PSP_NETPARAM_WIFI_CONFIG = 24; // int
    public static final int PSP_NETPARAM_MAX_NUMBER_DUMMY_ENTRIES = 10;
    protected static final int maxLineLengthForDialog = 40;
    protected static final int icon0Width = 144;
    protected static final int icon0Height = 80;
    protected static final int icon0PixelFormat = TPSM_PIXEL_STORAGE_MODE_32BIT_ABGR8888;
    protected static final int smallIcon0Width = 80;
    protected static final int smallIcon0Height = 44;
    // Round-up width to next valid buffer width
    protected static final int icon0BufferWidth = alignBufferWidth(icon0Width + IRenderingEngine.alignementOfTextureBufferWidth[icon0PixelFormat] - 1, icon0PixelFormat);
    protected GameSharingUtilityDialogState gameSharingState;
    protected UtilityDialogState netplayDialogState;
    protected NetconfUtilityDialogState netconfState;
    protected SavedataUtilityDialogState savedataState;
    protected MsgDialogUtilityDialogState msgDialogState;
    protected OskUtilityDialogState oskState;
    protected UtilityDialogState npSigninState;
    protected UtilityDialogState PS3ScanState;
    protected UtilityDialogState rssReaderState;
    protected UtilityDialogState rssSubscriberState;
    protected ScreenshotUtilityDialogState screenshotState;
    protected HtmlViewerUtilityDialogState htmlViewerState;
    protected UtilityDialogState savedataErrState;
    protected GamedataInstallUtilityDialogState gamedataInstallState;
	protected UtilityDialogState storeCheckoutState;
	protected UtilityDialogState psnState;
    protected UtilityDialogState startedDialogState;
    private static final String dummyNetParamName = "NetConf #%d";
    private final static int utilityThreadActionRegister = _s0; // $s0 is preserved across calls
    private final static int utilityThreadDelayRegister = _s1; // $s1 is preserved across calls
    private final static int UTILITY_THREAD_ACTION_INIT_START = 0;
    private final static int UTILITY_THREAD_ACTION_INIT_COMPLETE = 1;
    private final static int UTILITY_THREAD_ACTION_SHUTDOWN_START = 2;
    private final static int UTILITY_THREAD_ACTION_SHUTDOWN_COMPLETE = 3;
    protected Map<String, int[]> loadedModules = new HashMap<String, int[]>();
    protected Set<String> waitingModules = new HashSet<String>();

    public static final String[] utilityAvModuleNames = new String[] {
        "PSP_AV_MODULE_AVCODEC",
        "PSP_AV_MODULE_SASCORE",
        "PSP_AV_MODULE_ATRAC3PLUS",
        "PSP_AV_MODULE_MPEGBASE",
        "PSP_AV_MODULE_MP3",
        "PSP_AV_MODULE_VAUDIO",
        "PSP_AV_MODULE_AAC",
        "PSP_AV_MODULE_G729",
    };

    public static final String[] utilityUsbModuleNames = new String[] {
        "PSP_USB_MODULE_UNKNOWN_0",
        "PSP_USB_MODULE_PSPCM",
        "PSP_USB_MODULE_ACC",
        "PSP_USB_MODULE_MIC",
        "PSP_USB_MODULE_CAM",
        "PSP_USB_MODULE_GPS"
    };
    private static HashMap<String, String> utilityPrivateModules;

    public static final int PSP_AV_MODULE_AVCODEC = 0;
    public static final int PSP_AV_MODULE_SASCORE = 1;
    public static final int PSP_AV_MODULE_ATRAC3PLUS = 2;
    public static final int PSP_AV_MODULE_MPEGBASE = 3;
    public static final int PSP_AV_MODULE_MP3 = 4;
    public static final int PSP_AV_MODULE_VAUDIO = 5;
    public static final int PSP_AV_MODULE_AAC = 6;
    public static final int PSP_AV_MODULE_G729 = 7;

    public static final int PSP_USB_MODULE_PSPCM = 1;
    public static final int PSP_USB_MODULE_ACC = 2;
    public static final int PSP_USB_MODULE_MIC = 3;
    public static final int PSP_USB_MODULE_CAM = 4;
    public static final int PSP_USB_MODULE_GPS = 5;

    protected static class InstallUtilityDialogState extends UtilityDialogState {
		protected SceUtilityInstallParams installParams;

    	public InstallUtilityDialogState(String name) {
			super(name);
		}

		@Override
		protected boolean executeUpdateVisible() {
			boolean keepVisible = false;

			log.warn(String.format("Partial sceUtilityInstallUpdate %s", installParams.toString()));

			// We only get the game name from the install params. Is the rest fixed?
			String fileName = String.format("ms0:/PSP/GAME/%s/EBOOT.PBP", installParams.gameName);
	        try {
	            SeekableDataInput moduleInput = Modules.IoFileMgrForUserModule.getFile(fileName, IoFileMgrForUser.PSP_O_RDONLY);
	            if (moduleInput != null) {
	                byte[] moduleBytes = new byte[(int) moduleInput.length()];
	                moduleInput.readFully(moduleBytes);
	                ByteBuffer moduleBuffer = ByteBuffer.wrap(moduleBytes);

	    			// TODO How is this module being loaded?
	                // Does it unload the current module? i.e. re-init the PSP
	                SceModule module = Emulator.getInstance().load(name, moduleBuffer, true, false);
	                Emulator.getClock().resume();

	                if ((module.fileFormat & Loader.FORMAT_ELF) == Loader.FORMAT_ELF) {
	                	installParams.base.result = 0;
	                	keepVisible = false;
	                } else {
	                    log.warn("sceUtilityInstall - failed, target is not an ELF");
	                    installParams.base.result = -1;
	                }
	                moduleInput.close();
	            }
	        } catch (GeneralJpcspException e) {
	            log.error("General Error : " + e.getMessage());
	            Emulator.PauseEmu();
	        } catch (IOException e) {
	            log.error(String.format("sceUtilityInstall - Error while loading module %s: %s", fileName, e.getMessage()));
	            installParams.base.result = -1;
	        }

			return keepVisible;
		}

		@Override
		protected pspUtilityBaseDialog createParams() {
			installParams = new SceUtilityInstallParams();
			return installParams;
		}

        @Override
        protected boolean hasDialog() {
            return false;
        }
    }

    public static final String[] utilityNetModuleNames = new String[] {
        "PSP_NET_MODULE_UNKNOWN_0",
        "PSP_NET_MODULE_COMMON",
        "PSP_NET_MODULE_ADHOC",
        "PSP_NET_MODULE_INET",
        "PSP_NET_MODULE_PARSEURI",
        "PSP_NET_MODULE_PARSEHTTP",
        "PSP_NET_MODULE_HTTP",
        "PSP_NET_MODULE_SSL",
    };

    public static final int PSP_NET_MODULE_COMMON = 1;
    public static final int PSP_NET_MODULE_ADHOC = 2;
    public static final int PSP_NET_MODULE_INET = 3;
    public static final int PSP_NET_MODULE_PARSEURI = 4;
    public static final int PSP_NET_MODULE_PARSEHTTP = 5;
    public static final int PSP_NET_MODULE_HTTP = 6;
    public static final int PSP_NET_MODULE_SSL = 7;

    protected InstallUtilityDialogState installState;
    private int UTILITY_LOOP_ADDRESS;

	private String getNetModuleName(int module) {
    	if (module < 0 || module >= utilityNetModuleNames.length) {
    		return "PSP_NET_MODULE_UNKNOWN_" + module;
    	}
    	return utilityNetModuleNames[module];
    }

	public int loadModules(String moduleName) {
        HLEModuleManager moduleManager = HLEModuleManager.getInstance();
    	if (loadedModules.containsKey(moduleName) || waitingModules.contains(moduleName)) { // Module already loaded.
    		return SceKernelErrors.ERROR_NET_MODULE_ALREADY_LOADED;
    	} else if (!moduleManager.hasFlash0Module(moduleName)) { // Can't load flash0 module.
    		waitingModules.add(moduleName); // Always save a load attempt.
            return SceKernelErrors.ERROR_NET_MODULE_BAD_ID;
    	} else {
            // Load the modules and save them in loadedModules.
            int[] moduleIds = moduleManager.LoadFlash0Module(moduleName);
            loadedModules.put(moduleName, moduleIds);

            return 0;
        }
	}

	public int unloadModules(String moduleName) {
        if (loadedModules.containsKey(moduleName)) {
            // Unload the module.
            HLEModuleManager moduleManager = HLEModuleManager.getInstance();
            int[] moduleIds = loadedModules.remove(moduleName);
            if (moduleIds != null) {
            	for (int moduleId : moduleIds) {
                    SceModule sceModule = Managers.modules.getModuleByUID(moduleId);
                    if (sceModule != null) {
                    	moduleManager.UnloadFlash0Module(sceModule);
                    }
            	}
            }
            return 0;
        } else if (waitingModules.contains(moduleName)) {
            // Simulate a successful unload.
        	waitingModules.remove(moduleName);
            return 0;
        } else {
            return SceKernelErrors.ERROR_NET_MODULE_NOT_LOADED;
        }
	}

    private static Locale getUtilityLocale(int language) {
    	Locale utilityLocale = Locale.getDefault();

    	switch (language) {
	    	case PSP_SYSTEMPARAM_LANGUAGE_JAPANESE:            utilityLocale = Locale.JAPANESE;            break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_ENGLISH:             utilityLocale = Locale.ENGLISH;             break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_FRENCH:              utilityLocale = Locale.FRENCH;              break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_SPANISH:             utilityLocale = new Locale("es");           break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_GERMAN:              utilityLocale = Locale.GERMAN;              break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_ITALIAN:             utilityLocale = Locale.ITALIAN;             break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_DUTCH:               utilityLocale = new Locale("nl");           break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_PORTUGUESE:          utilityLocale = new Locale("pt");           break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_RUSSIAN:             utilityLocale = new Locale("ru");           break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_KOREAN:              utilityLocale = Locale.KOREAN;              break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_CHINESE_TRADITIONAL: utilityLocale = Locale.TRADITIONAL_CHINESE; break;
	    	case PSP_SYSTEMPARAM_LANGUAGE_CHINESE_SIMPLIFIED:  utilityLocale = Locale.CHINESE;             break;
    	}

    	return utilityLocale;
    }

    private static String getDateTimeFormatString() {
    	StringBuilder dateTimeFormat = new StringBuilder();

    	switch (getSystemParamDateFormat()) {
	    	case PSP_SYSTEMPARAM_DATE_FORMAT_DDMMYYYY:
	    		dateTimeFormat.append("%te/%<tm/%<tY");
	    		break;
	    	case PSP_SYSTEMPARAM_DATE_FORMAT_MMDDYYYY:
	    		dateTimeFormat.append("%tm/%<te/%<tY");
	    		break;
	    	case PSP_SYSTEMPARAM_DATE_FORMAT_YYYYMMDD:
	    		dateTimeFormat.append("%tY/%<tm/%<te");
	    		break;
    		default:
    			dateTimeFormat.append("%tF");
    			break;
    	}

    	dateTimeFormat.append(" ");

    	switch (getSystemParamTimeFormat()) {
	    	case PSP_SYSTEMPARAM_TIME_FORMAT_12HR:
	    		dateTimeFormat.append("%<tl:%<tM %<Tp");
	    		break;
	    	case PSP_SYSTEMPARAM_TIME_FORMAT_24HR:
	    		dateTimeFormat.append("%<tk:%<tM");
	    		break;
    		default:
    			dateTimeFormat.append("%<tR");
    			break;
    	}

    	return dateTimeFormat.toString();
    }

    private static String formatDateTime(Calendar dateTime) {
    	String formattedDateTime = String.format(getDateTimeFormatString(), dateTime);

    	// Java doesn't have a format for the month as single digit :-(
    	// E.g. February is always formatted as "02".
    	// The PSP is however displaying the month as a single digit,
    	// so we manually remove any leading 0 for the month.
    	if (formattedDateTime.startsWith("0")) {
    		formattedDateTime = formattedDateTime.substring(1);
    	}
    	formattedDateTime = formattedDateTime.replace("/0", "/");

    	return formattedDateTime;
    }

    @HLEFunction(nid = HLESyscallNid, version = 150)
    public void hleUtilityThread(Processor processor) {
    	SceKernelThreadInfo currentThread = Modules.ThreadManForUserModule.getCurrentThread();
    	int action = processor.cpu.getRegister(utilityThreadActionRegister);
    	int delay = processor.cpu.getRegister(utilityThreadDelayRegister);
    	if (log.isDebugEnabled()) {
    		log.debug(String.format("hleUtilityThread action=%d, delay=%d", action, delay));
    	}

    	switch (action) {
    		case UTILITY_THREAD_ACTION_INIT_START:
                // Starting the init action.
                // Lock the volatile mem, it is used until sceUtilityXXXShutdown
                int lockResult = Modules.sceSuspendForUserModule.hleKernelVolatileMemLock(0, false);
                if (lockResult < 0) {
                	log.error(String.format("hleUtilityThread init thread cannot lock the volatile mem 0x%08X", lockResult));
                }

    			currentThread.cpuContext.setRegister(utilityThreadActionRegister, UTILITY_THREAD_ACTION_INIT_COMPLETE);
                if (currentThread.isRunning()) {
	    			// Wait a short time before completing the init.
	    			if (delay > 0) {
	    				Modules.ThreadManForUserModule.hleKernelDelayThread(delay, false);
	    			}
                }
    			break;
    		case UTILITY_THREAD_ACTION_INIT_COMPLETE:
                // Completing the init action.
                // Move to status VISIBLE
            	startedDialogState.status = PSP_UTILITY_DIALOG_STATUS_VISIBLE;
            	startedDialogState.startVisibleTimeMillis = Emulator.getClock().currentTimeMillis();
                if (!startedDialogState.hasDialog()) {
                	startedDialogState.dialogState = UtilityDialogState.DialogState.quit;
                }

                processor.cpu._v0 = 0;
                Modules.ThreadManForUserModule.hleKernelExitDeleteThread();
    			break;
    		case UTILITY_THREAD_ACTION_SHUTDOWN_START:
                // Starting the shutdown action.
    			// Unlock the volatile mem
                int unlockResult = Modules.sceSuspendForUserModule.hleKernelVolatileMemUnlock(0);
                if (unlockResult < 0) {
                	log.error(String.format("hleUtilityThread shutdown thread cannot unlock the volatile mem 0x%08X", unlockResult));
                } else {
                	Memory mem = Memory.getInstance();
                	// The volatile memory is cleared after its use
                	mem.memset(KERNEL_VOLATILE_MEM_START, (byte) 0, KERNEL_VOLATILE_MEM_SIZE);
                }

                currentThread.cpuContext.setRegister(utilityThreadActionRegister, UTILITY_THREAD_ACTION_SHUTDOWN_COMPLETE);
                if (currentThread.isRunning()) {
	    			// Wait a short time before completing the shutdown.
	    			if (delay > 0) {
	    				Modules.ThreadManForUserModule.hleKernelDelayThread(delay, false);
	    			}
                }
    			break;
	    	case UTILITY_THREAD_ACTION_SHUTDOWN_COMPLETE:
	    		// Completing the shutdown action.
	    		startedDialogState.status = PSP_UTILITY_DIALOG_STATUS_NONE;
	    		processor.cpu._v0 = 0;
	    		Modules.ThreadManForUserModule.hleKernelExitDeleteThread();
	    		break;
    	}
    }

    protected abstract static class UtilityDialogState {

        protected String name;
        protected pspUtilityBaseDialog params;
        protected TPointer paramsAddr;
        protected int status;
        protected UtilityDialog dialog;
        protected int drawSpeed;
        protected int minimumVisibleDurationMillis;
        protected long startVisibleTimeMillis;
        protected int buttonPressed;
        protected GuUtilityDialog guDialog;
        protected boolean isYesSelected;

        protected static enum DialogState {

            init,
            display,
            confirmation,
            inProgress,
            completed,
            quit
        };
        protected DialogState dialogState;

        public UtilityDialogState(String name) {
            this.name = name;
            status = PSP_UTILITY_DIALOG_STATUS_NONE;
            dialogState = DialogState.init;
            setButtonPressed(SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_INVALID);
        }

        protected void openDialog(UtilityDialog dialog) {
            if (dialogState == DialogState.init) {
                dialogState = DialogState.display;
            }

            status = PSP_UTILITY_DIALOG_STATUS_VISIBLE;
            this.dialog = dialog;
            dialog.setVisible(true);
        }

        protected void openDialog(GuUtilityDialog guDialog) {
            if (dialogState == DialogState.init) {
                dialogState = DialogState.display;
            }

            status = PSP_UTILITY_DIALOG_STATUS_VISIBLE;
            this.guDialog = guDialog;
        }

        protected boolean isDialogOpen() {
            return dialog != null || guDialog != null;
        }

        protected void updateDialog() {
            int delayMicros = 1000000 / 60;
//            if (drawSpeed > 0) {
//                delayMicros *= drawSpeed;
//            }
            Modules.ThreadManForUserModule.hleKernelDelayThread(delayMicros, false);
        }

        protected boolean isDialogActive() {
            if (isDialogOpen()) {
                if (dialog != null) {
                    return dialog.isVisible();
                }

                if (guDialog != null) {
                    return guDialog.isVisible();
                }
            }

            return false;
        }

        protected void closeDialog() {
            if (dialog != null) {
                dialog = null;
            }
            if (guDialog != null) {
                guDialog = null;
            }
        }

        private void setResult(int result) {
            if (params != null && params.base != null) {
                params.base.result = result;
                params.base.writeResult(paramsAddr);
            }
        }

        protected void quitDialog() {
            closeDialog();
            status = PSP_UTILITY_DIALOG_STATUS_QUIT;
            dialogState = DialogState.quit;
        }

        protected void quitDialog(int result) {
            quitDialog();
            setResult(result);
        }

        public int getButtonPressed() {
            return buttonPressed;
        }

        final public void setButtonPressed(int buttonPressed) {
            this.buttonPressed = buttonPressed;
        }

        public int executeInitStart(TPointer paramsAddr) {
            if (status != PSP_UTILITY_DIALOG_STATUS_NONE) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%sInitStart already started status=%d", name, status));
                }
                return SceKernelErrors.ERROR_UTILITY_INVALID_STATUS;
            }

            this.paramsAddr = paramsAddr;
            this.params = createParams();

            params.read(paramsAddr);

            if (log.isInfoEnabled()) {
                log.info(String.format("%sInitStart %s-0x%08X: %s", name, paramsAddr, paramsAddr.getAddress() + params.sizeof(), params.toString()));
            }

            int validityResult = checkValidity();

            if (validityResult == 0) {
                // Start with INIT
                status = PSP_UTILITY_DIALOG_STATUS_INIT;
                dialogState = DialogState.init;
                sceUtilityModule.startedDialogState = this;

                // Execute the init thread, it will update the status
                SceKernelThreadInfo initThread = ThreadManForUserModule.hleKernelCreateThread("SceUtilityInit", sceUtilityModule.UTILITY_LOOP_ADDRESS, params.base.accessThread, 0x800, 0, 0, USER_PARTITION_ID);
                ThreadManForUserModule.hleKernelStartThread(initThread, 0, TPointer.NULL, initThread.gpReg_addr);
                initThread.cpuContext.setRegister(utilityThreadActionRegister, UTILITY_THREAD_ACTION_INIT_START);
                initThread.cpuContext.setRegister(utilityThreadDelayRegister, getInitDelay());
            }

            return validityResult;
        }

        protected boolean isReadyForVisible() {
            // Wait for all the buttons to be released
            if ((State.controller.getButtons() & 0x000FFFF) != 0) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Not ready for visible, button pressed 0x%X", State.controller.getButtons()));
                }
                return false;
            }

            return true;
        }

        protected boolean hasDialog() {
            return true;
        }

        public int executeGetStatus() {
        	// Return ERROR_UTILITY_WRONG_TYPE if no sceUtilityXXXInitStart has ever been started or
            // if a different type of dialog was started.
            if (Modules.sceUtilityModule.startedDialogState == null || Modules.sceUtilityModule.startedDialogState != this) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%sGetStatus returning ERROR_UTILITY_WRONG_TYPE", name));
                }
                return SceKernelErrors.ERROR_UTILITY_WRONG_TYPE;
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("%sGetStatus status %d", name, status));
            }

            int previousStatus = status;

            // Remark: moving from INIT status to VISIBLE is performed in the init thread.
            // Remark: moving from FINISHED status to NONE is performed in the shutdown thread.

            // After moving to status NONE, subsequent calls of sceUtilityXXXGetStatus
            // keep returning status NONE (if of the same type) and not ERROR_UTILITY_WRONG_TYPE.
            // Keep the current value in Modules.sceUtilityModule.startedDialogState for this purpose.
            return previousStatus;
        }

        public int executeShutdownStart() {
            if (Modules.sceUtilityModule.startedDialogState == null || Modules.sceUtilityModule.startedDialogState != this) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%ShutdownStart returning ERROR_UTILITY_WRONG_TYPE", name));
                }
                return SceKernelErrors.ERROR_UTILITY_WRONG_TYPE;
            }

            if (status != PSP_UTILITY_DIALOG_STATUS_QUIT) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%ShutdownStart returning ERROR_UTILITY_INVALID_STATUS", name));
                }
                return SceKernelErrors.ERROR_UTILITY_INVALID_STATUS;
            }

            status = PSP_UTILITY_DIALOG_STATUS_FINISHED;

            // Execute the shutdown thread, it will set the status to 0.
            SceKernelThreadInfo shutdownThread = ThreadManForUserModule.hleKernelCreateThread("SceUtilityShutdown", sceUtilityModule.UTILITY_LOOP_ADDRESS, params.base.accessThread, 0x800, 0, 0, USER_PARTITION_ID);
            ThreadManForUserModule.hleKernelStartThread(shutdownThread, 0, TPointer.NULL, shutdownThread.gpReg_addr);
            shutdownThread.cpuContext.setRegister(utilityThreadActionRegister, UTILITY_THREAD_ACTION_SHUTDOWN_START);
            shutdownThread.cpuContext.setRegister(utilityThreadDelayRegister, getShutdownDelay());

            return 0;
        }

        /**
         * @param drawSpeed FPS used for internal animation sync (1 = 60 FPS; 2 = 30 FPS; 3 = 15 FPS)
         * @return
         */
        public final int executeUpdate(int drawSpeed) {
        	this.drawSpeed = drawSpeed;

        	if (Modules.sceUtilityModule.startedDialogState == null || Modules.sceUtilityModule.startedDialogState != this) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%sUpdate returning ERROR_UTILITY_WRONG_TYPE", name));
                }
                return SceKernelErrors.ERROR_UTILITY_WRONG_TYPE;
            }

            // PSP is returning ERROR_UTILITY_INVALID_STATUS when not in STATUS_VISIBLE
            int result = SceKernelErrors.ERROR_UTILITY_INVALID_STATUS;

            if (status == PSP_UTILITY_DIALOG_STATUS_INIT && isReadyForVisible()) {
                // Move from INIT to VISIBLE
                status = PSP_UTILITY_DIALOG_STATUS_VISIBLE;
                startVisibleTimeMillis = Emulator.getClock().currentTimeMillis();
            } else if (status == PSP_UTILITY_DIALOG_STATUS_VISIBLE || status == PSP_UTILITY_DIALOG_STATUS_SCREENSHOT_UNKNOWN) {
                // PSP is returning 0 only in STATUS_VISIBLE
                result = 0;

                // Some games reach sceUtilitySavedataInitStart with empty params which only
                // get filled with a subsequent call to sceUtilitySavedataUpdate (eg.: To Love-Ru).
                // This is why we have to re-read the params here.
                params.read(paramsAddr);

                if (guDialog != null) {
                    guDialog.update(drawSpeed);
                }

                boolean keepVisible = executeUpdateVisible();

                if (status == PSP_UTILITY_DIALOG_STATUS_VISIBLE && isDialogOpen()) {
                    if (dialog != null) {
                        dialog.checkController();
                    }
                    if (guDialog != null) {
                        guDialog.checkController();
                    }
                }

                if (status == PSP_UTILITY_DIALOG_STATUS_VISIBLE && !isDialogOpen() && !keepVisible && dialogState == DialogState.quit) {
                    // Check if we stayed long enough in the VISIBLE state
                    long now = Emulator.getClock().currentTimeMillis();
                    if (now - startVisibleTimeMillis >= getMinimumVisibleDurationMillis()) {
                        // There was no dialog or it has completed
                        status = PSP_UTILITY_DIALOG_STATUS_QUIT;
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("%sUpdate returning 0x%08X", name, result));
            }
            return result;
        }

        public int executeAbort() {
            if (Modules.sceUtilityModule.startedDialogState == null || Modules.sceUtilityModule.startedDialogState != this) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%sAbort returning ERROR_UTILITY_WRONG_TYPE", name));
                }
                return SceKernelErrors.ERROR_UTILITY_WRONG_TYPE;
            }

            if (status != PSP_UTILITY_DIALOG_STATUS_VISIBLE) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("%sAbort returning ERROR_UTILITY_INVALID_STATUS", name));
                }
                return SceKernelErrors.ERROR_UTILITY_INVALID_STATUS;
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("%sAbort", name));
            }

            quitDialog(PSP_UTILITY_DIALOG_RESULT_ABORTED);

            return 0;
        }

        public void cancel() {
            quitDialog(PSP_UTILITY_DIALOG_RESULT_CANCELED);
        }

        protected abstract boolean executeUpdateVisible();

        protected abstract pspUtilityBaseDialog createParams();

        protected int checkValidity() {
            return 0;
        }

        public int getMinimumVisibleDurationMillis() {
            return minimumVisibleDurationMillis;
        }

        public void setMinimumVisibleDurationMillis(int minimumVisibleDurationMillis) {
            this.minimumVisibleDurationMillis = minimumVisibleDurationMillis;
        }

        protected String getDialogTitle(String key, String defaultTitle, Locale utilityLocale) {
            String title;
            try {
                ResourceBundle bundle = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale);
                if (key == null) {
                    title = bundle.getString(name);
                } else {
                    title = bundle.getString(name + "." + key);
                }
            } catch (MissingResourceException mre) {
                title = defaultTitle;
            }
            return title;
        }

        public boolean isYesSelected() {
            return isYesSelected;
        }

        public boolean isNoSelected() {
            return !isYesSelected;
        }

        public void setYesSelected(boolean isYesSelected) {
            this.isYesSelected = isYesSelected;
        }

        protected int getShutdownDelay() {
        	if (hasDialog()) {
        		// The shutdown is taking some time to complete
        		// when a dialog has been shown to the user.
            	return 50000;
        	}

        	return 0;
        }

        protected int getInitDelay() {
        	return 0;
        }
    }

    protected static class NotImplementedUtilityDialogState extends UtilityDialogState {

        public NotImplementedUtilityDialogState(String name) {
            super(name);
        }

        @Override
        public int executeInitStart(TPointer paramsAddr) {
            log.warn(String.format("Unimplemented: %sInitStart params: %s", name, Utilities.getMemoryDump(paramsAddr.getAddress(), paramsAddr.getValue32())));

            return SceKernelErrors.ERROR_UTILITY_IS_UNKNOWN;
        }

        @Override
        public int executeShutdownStart() {
            log.warn(String.format("Unimplemented: %sShutdownStart", name));

            return SceKernelErrors.ERROR_UTILITY_IS_UNKNOWN;
        }

        @Override
        public int executeGetStatus() {
            log.warn(String.format("Unimplemented: %sGetStatus", name));

            return SceKernelErrors.ERROR_UTILITY_IS_UNKNOWN;
        }

        @Override
        protected boolean executeUpdateVisible() {
            log.warn(String.format("Unimplemented: %sUpdate", name));

            return false;
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            return null;
        }

        @Override
        protected boolean hasDialog() {
            return false;
        }
    }

    protected static class SavedataUtilityDialogState extends UtilityDialogState {

        protected SceUtilitySavedataParam savedataParams;
        protected volatile String saveListSelection;
        protected volatile InputStream saveListSelectionIcon0;
        protected boolean saveListEmpty;

        public SavedataUtilityDialogState(String name) {
            super(name);

            // Stay at least 500ms in the VISIBLE state.
            // E.g. do not complete too quickly the AUTOLOAD/AUTOSAVE modes.
            setMinimumVisibleDurationMillis(500);
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            savedataParams = new SceUtilitySavedataParam();
            return savedataParams;
        }

        @Override
        protected int checkValidity() {
            int paramSize = savedataParams.base.totalSizeof();
            // Only these parameter sizes are allowed:
            if (paramSize != 1480 && paramSize != 1500 && paramSize != 1536) {
                log.warn(String.format("sceUtilitySavedataInitStart invalid parameter size %d", paramSize));
                return SceKernelErrors.ERROR_UTILITY_INVALID_PARAM_SIZE;
            }

            return super.checkValidity();
        }
        // All SAVEDATA modes after MODE_SINGLEDELETE can be called multiple times and keep track of that.
        private int savedataMultiStatus;

        protected int checkMultipleCallStatus() {
            // Check the current multiple call status.
            if (savedataParams.multiStatus == SceUtilitySavedataParam.MULTI_STATUS_SINGLE
                    || savedataParams.multiStatus == SceUtilitySavedataParam.MULTI_STATUS_INIT) {
                // If the multiple call status is SINGLE or INIT, just save it.
                savedataMultiStatus = savedataParams.multiStatus;
                return 0;
            }
            if (savedataParams.multiStatus == SceUtilitySavedataParam.MULTI_STATUS_RELAY
                    || savedataParams.multiStatus == SceUtilitySavedataParam.MULTI_STATUS_FINISH) {
                // If the multiple call status is RELAY or FINISH, check if INIT or another RELAY has been called.
                if (savedataMultiStatus <= savedataParams.multiStatus) {
                    savedataMultiStatus = savedataParams.multiStatus;
                    return 0;
                }
            }

            return SceKernelErrors.ERROR_SAVEDATA_RW_BAD_STATUS;
        }

        @Override
        protected boolean executeUpdateVisible() {
            Memory mem = Processor.memory;

            switch (savedataParams.mode) {
                case SceUtilitySavedataParam.MODE_AUTOLOAD: {
                    if (savedataParams.saveName == null
                            || savedataParams.saveName.equals(SceUtilitySavedataParam.anyFileName)
                            || savedataParams.saveName.length() == 0) {
                        if (savedataParams.saveNameList != null && savedataParams.saveNameList.length > 0) {
                            savedataParams.saveName = savedataParams.saveNameList[0];
                        }
                    }

                    try {
                        savedataParams.load(mem);
                        savedataParams.base.result = 0;
                        savedataParams.write(mem);
                    } catch (IOException e) {
                        if (!savedataParams.isGameDirectoryPresent()) {
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_DATA;
                        } else if (savedataParams.base.totalSizeof() < 1536) {
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_DATA;
                        } else {
                            // The PSP is returning a different return code based on the size of the savedataParams input structure.
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_UMD;
                        }
                    } catch (Exception e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_ACCESS_ERROR;
                        log.error(e);
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_LOAD: {
                    switch (dialogState) {
                        case init: {
                            if (savedataParams.saveName == null || savedataParams.saveName.length() == 0) {
                                if (savedataParams.saveNameList != null && savedataParams.saveNameList.length > 0) {
                                    savedataParams.saveName = savedataParams.saveNameList[0];
                                }
                            }

                            setYesSelected(true);
                            GuSavedataDialogLoad gu = new GuSavedataDialogLoad(savedataParams, this);
                            openDialog(gu);
                            dialogState = DialogState.confirmation;
                            break;
                        }
                        case confirmation: {
                            if (!isDialogActive()) {
                                if (getButtonPressed() != SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK || isNoSelected()) {
                                    // The dialog has been cancelled or the user did not want to load.
                                    cancel();
                                } else {
                                    closeDialog();
                                    dialogState = DialogState.inProgress;
                                }
                            } else {
                                updateDialog();
                            }
                            break;
                        }
                        case inProgress: {
                            try {
                                savedataParams.load(mem);
                                savedataParams.base.result = 0;
                                savedataParams.write(mem);
                            } catch (IOException e) {
                                if (!savedataParams.isGameDirectoryPresent()) {
                                    savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_DATA;
                                } else if (savedataParams.base.totalSizeof() < 1536) {
                                    savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_DATA;
                                } else {
                                    // The PSP is returning a different return code based on the size of the savedataParams input structure.
                                    savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_UMD;
                                }
                            } catch (Exception e) {
                                savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_ACCESS_ERROR;
                                log.error(e);
                            }

                            if (isReadyForVisible()) {
                                GuSavedataDialogCompleted gu = new GuSavedataDialogCompleted(savedataParams, this, saveListSelectionIcon0);
                                openDialog(gu);
                                dialogState = DialogState.completed;
                            }
                            break;
                        }
                        case completed: {
                            if (!isDialogActive()) {
                                quitDialog();
                            } else {
                                updateDialog();
                            }
                            break;
                        }
					case display:
					case quit:
						// Nothing to do
						break;
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_LISTLOAD: {
                    switch (dialogState) {
                        case init: {
                            // Search for valid saves.
                            ArrayList<String> validNames = new ArrayList<String>();

                            for (int i = 0; i < savedataParams.saveNameList.length; i++) {
                                savedataParams.saveName = savedataParams.saveNameList[i];

                                if (savedataParams.isPresent()) {
                                    validNames.add(savedataParams.saveName);
                                }
                            }

                            if (isReadyForVisible()) {
	                            GuSavedataDialog gu = new GuSavedataDialog(savedataParams, this, validNames.toArray(new String[validNames.size()]));
	                            openDialog(gu);
                            }
                            break;
                        }
                        case display: {
                            if (!isDialogActive()) {
                                if (getButtonPressed() != SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK) {
                                    if (saveListEmpty) {
                                        // No data available
                                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_DATA;
                                    } else {
                                        // Dialog cancelled
                                        savedataParams.base.result = ERROR_SAVEDATA_CANCELLED;
                                    }
                                    quitDialog(savedataParams.base.result);
                                } else if (saveListSelection == null) {
                                    log.warn("Savedata MODE_LISTLOAD no save selected");
                                    quitDialog(SceKernelErrors.ERROR_SAVEDATA_LOAD_BAD_PARAMS);
                                } else {
                                    closeDialog();
                                    dialogState = DialogState.inProgress;
                                }
                            } else {
                                updateDialog();
                            }
                            break;
                        }
                        case inProgress: {
                            try {
                                savedataParams.saveName = saveListSelection;
                                if (log.isDebugEnabled()) {
                                    log.debug(String.format("Loading savedata %s", savedataParams.saveName));
                                }
                                savedataParams.load(mem);
                                savedataParams.base.result = 0;
                                savedataParams.write(mem);
                            } catch (IOException e) {
                                if (!savedataParams.isGameDirectoryPresent()) {
                                    savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_DATA;
                                } else if (savedataParams.base.totalSizeof() < 1536) {
                                    savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_DATA;
                                } else {
                                    // The PSP is returning a different return code based on the size of the savedataParams input structure.
                                    savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_NO_UMD;
                                }
                            } catch (Exception e) {
                                savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_LOAD_ACCESS_ERROR;
                                log.error(e);
                            }

                            if (isReadyForVisible()) {
                                GuSavedataDialogCompleted gu = new GuSavedataDialogCompleted(savedataParams, this, saveListSelectionIcon0);
                                openDialog(gu);
                                dialogState = DialogState.completed;
                            }
                            break;
                        }
                        case completed: {
                            if (!isDialogActive()) {
                                quitDialog();
                            } else {
                                updateDialog();
                            }
                            break;
                        }
					case confirmation:
					case quit:
						// Nothing to do
						break;
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_AUTOSAVE: {
                    if (savedataParams.saveName == null
                            || savedataParams.saveName.equals(SceUtilitySavedataParam.anyFileName)
                            || savedataParams.saveName.length() == 0) {
                        if (savedataParams.saveNameList != null && savedataParams.saveNameList.length > 0) {
                            savedataParams.saveName = savedataParams.saveNameList[0];
                        }
                    }

                    try {
                        savedataParams.save(mem, true);
                        savedataParams.base.result = 0;
                    } catch (IOException e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_SAVE_ACCESS_ERROR;
                    } catch (Exception e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_SAVE_ACCESS_ERROR;
                        log.error(e);
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_SAVE: {
                    switch (dialogState) {
                        case init: {
                            if (savedataParams.saveName == null || savedataParams.saveName.length() == 0) {
                                if (savedataParams.saveNameList != null && savedataParams.saveNameList.length > 0) {
                                    savedataParams.saveName = savedataParams.saveNameList[0];
                                }
                            }

                            // Yes is selected by default if the save does not exist.
                            // No is selected by default if the save does exist (overwrite).
                            setYesSelected(!savedataParams.isPresent());
                            GuSavedataDialogSave gu = new GuSavedataDialogSave(savedataParams, this);
                            openDialog(gu);
                            dialogState = DialogState.confirmation;
                            break;
                        }
                        case confirmation: {
                            if (!isDialogActive()) {
                                if (getButtonPressed() != SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK || isNoSelected()) {
                                    // The dialog has been cancelled or the user did not want to save.
                                    cancel();
                                } else {
                                    closeDialog();
                                    dialogState = DialogState.inProgress;
                                }
                            } else {
                                updateDialog();
                            }
                            break;
                        }
                        case inProgress: {
                            try {
                                savedataParams.save(mem, true);
                                savedataParams.base.result = 0;
                            } catch (IOException e) {
                                savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_SAVE_ACCESS_ERROR;
                            } catch (Exception e) {
                                savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_SAVE_ACCESS_ERROR;
                                log.error(e);
                            }

                            if (isReadyForVisible()) {
                                GuSavedataDialogCompleted gu = new GuSavedataDialogCompleted(savedataParams, this, saveListSelectionIcon0);
                                openDialog(gu);
                                dialogState = DialogState.completed;
                            }
                            break;
                        }
                        case completed: {
                            if (!isDialogActive()) {
                                quitDialog();
                            } else {
                                updateDialog();
                            }
                            break;
                        }
					case display:
					case quit:
						// Nothing to do
						break;
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_LISTSAVE: {
                    switch (dialogState) {
                        case init: {
                        	if (isReadyForVisible()) {
	                            GuSavedataDialog gu = new GuSavedataDialog(savedataParams, this, savedataParams.saveNameList);
	                            openDialog(gu);
                        	}
                            break;
                        }
                        case display: {
                            if (!isDialogActive()) {
                                closeDialog();
                                if (getButtonPressed() != SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK) {
                                    // Dialog cancelled
                                    quitDialog(SceKernelErrors.ERROR_SAVEDATA_SAVE_BAD_PARAMS);
                                } else if (saveListSelection == null) {
                                    log.warn("Savedata MODE_LISTSAVE no save selected");
                                    quitDialog(ERROR_SAVEDATA_CANCELLED);
                                } else {
                                    savedataParams.saveName = saveListSelection;
                                    savedataParams.write(mem);
                                    if (savedataParams.isPresent(savedataParams.gameName, saveListSelection)) {
                                        if (isReadyForVisible()) {
                                            GuSavedataDialogSave gu = new GuSavedataDialogSave(savedataParams, this);
                                            openDialog(gu);
                                            dialogState = DialogState.confirmation;
                                        }
                                    } else {
                                        dialogState = DialogState.inProgress;
                                    }
                                }
                            } else {
                                updateDialog();
                            }
                            break;
                        }
                        case confirmation: {
                            if (!isDialogActive()) {
                                if (getButtonPressed() != SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK || isNoSelected()) {
                                    // The dialog has been cancelled or the user did not want to save.
                                    cancel();
                                } else {
                                    closeDialog();
                                    dialogState = DialogState.inProgress;
                                }
                            } else {
                                updateDialog();
                            }
                            break;
                        }
                        case inProgress: {
                            try {
                                if (log.isDebugEnabled()) {
                                    log.debug(String.format("Saving savedata %s", savedataParams.saveName));
                                }
                                savedataParams.save(mem, true);
                                savedataParams.base.result = 0;
                            } catch (IOException e) {
                                savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_SAVE_ACCESS_ERROR;
                            } catch (Exception e) {
                                savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_SAVE_ACCESS_ERROR;
                                log.error(e);
                            }

                            if (isReadyForVisible()) {
                                GuSavedataDialogCompleted gu = new GuSavedataDialogCompleted(savedataParams, this, saveListSelectionIcon0);
                                openDialog(gu);
                                dialogState = DialogState.completed;
                            }
                            break;
                        }
                        case completed: {
                            if (!isDialogActive()) {
                                quitDialog();
                            } else {
                                updateDialog();
                            }
                            break;
                        }
					case quit:
						// Nothing to do
						break;
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_DELETE: {
                    if (!isDialogOpen()) {
                        // Search for valid saves.
                        String pattern = savedataParams.gameName + ".*";

                        String[] entries = Modules.IoFileMgrForUserModule.listFiles(SceUtilitySavedataParam.savedataPath, pattern);
                        ArrayList<String> validNames = new ArrayList<String>();
                        for (int i = 0; entries != null && i < entries.length; i++) {
                            String saveName = entries[i].substring(savedataParams.gameName.length());
                            if (savedataParams.isPresent(savedataParams.gameName, saveName)) {
                                validNames.add(saveName);
                            }
                        }

                        GuSavedataDialog gu = new GuSavedataDialog(savedataParams, this, validNames.toArray(new String[validNames.size()]));
                        openDialog(gu);
                    } else if (!isDialogActive()) {
                        if (getButtonPressed() != SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK) {
                            // Dialog cancelled
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_DELETE_BAD_PARAMS;
                        } else if (saveListSelection == null) {
                            log.warn("Savedata MODE_DELETE no save selected");
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_DELETE_BAD_PARAMS;
                        } else {
                            String dirName = savedataParams.getBasePath(saveListSelection);
                            if (savedataParams.deleteDir(dirName)) {
                                log.debug("Savedata MODE_DELETE deleting " + dirName);
                                savedataParams.base.result = 0;
                            } else {
                                savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_DELETE_ACCESS_ERROR;
                            }
                        }
                        quitDialog(savedataParams.base.result);
                    } else {
                        updateDialog();
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_SIZES: {
                    // "METAL SLUG XX" outputs the following on stdout after calling mode 8:
                    //
                    // ------ SIZES ------
                    // ---------- savedata result ----------
                    // result = 0x801103c7
                    //
                    // bind : un used(0x0).
                    //
                    // -- dir name --
                    // title id : ULUS10495
                    // user  id : METALSLUGXX
                    //
                    // ms free size
                    //   cluster size(byte) : 32768 byte
                    //   free cluster num   : 32768
                    //   free size(KB)      : 1048576 KB
                    //   free size(string)  : "1 GB"
                    //
                    // ms data size(titleId=ULUS10495, userId=METALSLUGXX)
                    //   cluster num        : 0
                    //   size (KB)          : 0 KB
                    //   size (string)      : "0 KB"
                    //   size (32KB)        : 0 KB
                    //   size (32KB string) : "0 KB"
                    //
                    // utility data size
                    //   cluster num        : 13
                    //   size (KB)          : 416 KB
                    //   size (string)      : "416 KB"
                    //   size (32KB)        : 416 KB
                    //   size (32KB string) : "416 KB"
                    // error: SCE_UTILITY_SAVEDATA_TYPE_SIZES return 801103c7
                    //
                    int retval = 0;

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("MODE_SIZES: msFreeAddr=0x%08X-0x%08X, msDataAddr=0x%08X-0x%08X, utilityDataAddr=0x%08X-0x%08X", savedataParams.msFreeAddr, savedataParams.msFreeAddr + 20, savedataParams.msDataAddr, savedataParams.msDataAddr + 64, savedataParams.utilityDataAddr, savedataParams.utilityDataAddr + 28));
                    }

                    // Gets the amount of free space on the Memory Stick.
                    int msFreeAddr = savedataParams.msFreeAddr;
                    if (msFreeAddr != 0) {
                        String memoryStickFreeSpaceString = MemoryStick.getSizeKbString(MemoryStick.getFreeSizeKb());

                        mem.write32(msFreeAddr + 0, MemoryStick.getSectorSize());
                        mem.write32(msFreeAddr + 4, MemoryStick.getFreeSizeKb() / MemoryStick.getSectorSizeKb());
                        mem.write32(msFreeAddr + 8, MemoryStick.getFreeSizeKb());
                        Utilities.writeStringNZ(mem, msFreeAddr + 12, 8, memoryStickFreeSpaceString);

                        log.debug("Memory Stick Free Space = " + memoryStickFreeSpaceString);
                    }

                    // Gets the size of the data already saved on the Memory Stick.
                    int msDataAddr = savedataParams.msDataAddr;
                    if (msDataAddr != 0) {
                        String gameName = Utilities.readStringNZ(mem, msDataAddr, 13);
                        String saveName = Utilities.readStringNZ(mem, msDataAddr + 16, 20);

                        saveName = savedataParams.getAnySaveName(gameName, saveName);
                        if (savedataParams.isDirectoryPresent(gameName, saveName)) {
                            int savedataSizeKb = savedataParams.getSizeKb(gameName, saveName);
                            int savedataSize32Kb = MemoryStick.getSize32Kb(savedataSizeKb);

                            mem.write32(msDataAddr + 36, savedataSizeKb / MemoryStick.getSectorSizeKb()); // Number of sectors.
                            mem.write32(msDataAddr + 40, savedataSizeKb); // Size in Kb.
                            Utilities.writeStringNZ(mem, msDataAddr + 44, 8, MemoryStick.getSizeKbString(savedataSizeKb));
                            mem.write32(msDataAddr + 52, savedataSize32Kb);
                            Utilities.writeStringNZ(mem, msDataAddr + 56, 8, MemoryStick.getSizeKbString(savedataSize32Kb));

                            log.debug("Memory Stick Used Space = " + MemoryStick.getSizeKbString(savedataSizeKb));
                        } else {
                            log.debug(String.format("Savedata MODE_SIZES directory not found, gameName='%s', saveName='%s'", gameName, saveName));
                            retval = SceKernelErrors.ERROR_SAVEDATA_SIZES_NO_DATA;
                        }
                    }

                    // Gets the size of the data to be saved on the Memory Stick.
                    int utilityDataAddr = savedataParams.utilityDataAddr;
                    if (utilityDataAddr != 0) {
                        int memoryStickRequiredSpaceKb = savedataParams.getRequiredSizeKb();
                        String memoryStickRequiredSpaceString = MemoryStick.getSizeKbString(memoryStickRequiredSpaceKb);
                        int memoryStickRequiredSpace32Kb = MemoryStick.getSize32Kb(memoryStickRequiredSpaceKb);
                        String memoryStickRequiredSpace32KbString = MemoryStick.getSizeKbString(memoryStickRequiredSpace32Kb);

                        mem.write32(utilityDataAddr + 0, memoryStickRequiredSpaceKb / MemoryStick.getSectorSizeKb());
                        mem.write32(utilityDataAddr + 4, memoryStickRequiredSpaceKb);
                        Utilities.writeStringNZ(mem, utilityDataAddr + 8, 8, memoryStickRequiredSpaceString);
                        mem.write32(utilityDataAddr + 16, memoryStickRequiredSpace32Kb);
                        Utilities.writeStringNZ(mem, utilityDataAddr + 20, 8, memoryStickRequiredSpace32KbString);

                        log.debug("Memory Stick Required Space = " + memoryStickRequiredSpaceString);
                    }
                    savedataParams.base.result = retval;
                    break;
                }

                case SceUtilitySavedataParam.MODE_AUTODELETE: {
                    if (savedataParams.deleteDir(savedataParams.getBasePath())) {
                        savedataParams.base.result = 0;
                    } else {
                        log.warn("Savedata MODE_AUTODELETE directory not found!");
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_DELETE_NO_DATA;
                    }
                    // Tests show certain applications expect the PSP to change the
                    // dialog status automatically after delete.
                    status = PSP_UTILITY_DIALOG_STATUS_QUIT;
                    break;
                }

                case SceUtilitySavedataParam.MODE_SINGLEDELETE: {
                    if (savedataParams.deleteFile(savedataParams.fileName)) {
                        savedataParams.base.result = 0;
                    } else {
                        log.warn("Savedata MODE_SINGLEDELETE file not found!");
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_DELETE_NO_MEMSTICK;
                    }
                    // Tests show certain applications expect the PSP to change the
                    // dialog status automatically after delete.
                    status = PSP_UTILITY_DIALOG_STATUS_QUIT;
                    break;
                }

                case SceUtilitySavedataParam.MODE_LIST: {
                    int buffer4Addr = savedataParams.idListAddr;
                    if (Memory.isAddressGood(buffer4Addr)) {
                        int maxEntries = mem.read32(buffer4Addr + 0);
                        int entriesAddr = mem.read32(buffer4Addr + 8);
                        String saveName = savedataParams.saveName;
                        // PSP file name pattern:
                        //   '?' matches one character
                        //   '*' matches any character sequence
                        // To convert to regular expressions:
                        //   replace '?' with '.'
                        //   replace '*' with '.*'
                        String pattern = saveName.replace('?', '.');
                        pattern = pattern.replace("*", ".*");
                        pattern = savedataParams.gameName + pattern;

                        String[] entries = Modules.IoFileMgrForUserModule.listFiles(SceUtilitySavedataParam.savedataPath, pattern);
                        int numEntries = entries == null ? 0 : entries.length;
                        numEntries = Math.min(numEntries, maxEntries);
                        for (int i = 0; i < numEntries; i++) {
                            String filePath = SceUtilitySavedataParam.savedataPath + "/" + entries[i];
                            SceIoStat stat = Modules.IoFileMgrForUserModule.statFile(filePath);
                            int entryAddr = entriesAddr + i * 72;
                            if (stat != null) {
                                mem.write32(entryAddr + 0, stat.mode);
                                stat.ctime.write(mem, entryAddr + 4);
                                stat.atime.write(mem, entryAddr + 20);
                                stat.mtime.write(mem, entryAddr + 36);
                            }
                            String entryName = entries[i].substring(savedataParams.gameName.length());
                            // File names are upper cased in some conditions
                            entryName = getMsFileName(entryName);
                            Utilities.writeStringNZ(mem, entryAddr + 52, 20, entryName);

                            if (log.isDebugEnabled()) {
                                log.debug(String.format("MODE_LIST returning filePath=%s, stat=%s, entryName=%s at 0x%08X", filePath, stat, entryName, entryAddr));
                            }
                        }
                        mem.write32(buffer4Addr + 4, numEntries);

                        if (log.isDebugEnabled()) {
                            log.debug(String.format("MODE_LIST returning %d entries", numEntries));
                        }
                    }
                    savedataParams.base.result = checkMultipleCallStatus();
                    break;
                }

                case SceUtilitySavedataParam.MODE_FILES: {
                    int fileListAddr = savedataParams.fileListAddr;
                    if (Memory.isAddressGood(fileListAddr)) {
                        int saveFileSecureMaxNumEntries = mem.read32(fileListAddr);
                        int saveFileMaxNumEntries = mem.read32(fileListAddr + 4);
                        int systemMaxNumEntries = mem.read32(fileListAddr + 8);

                        if (log.isDebugEnabled()) {
                            log.debug(String.format("MaxFiles in FileList: secure=%d, normal=%d, system=%d", saveFileSecureMaxNumEntries, saveFileMaxNumEntries, systemMaxNumEntries));
                        }

                        int saveFileSecureEntriesAddr = mem.read32(fileListAddr + 24);
                        int saveFileEntriesAddr = mem.read32(fileListAddr + 28);
                        int systemEntriesAddr = mem.read32(fileListAddr + 32);

                        String path = savedataParams.getBasePath();
                        String[] entries = Modules.IoFileMgrForUserModule.listFiles(path, null);

                        int maxNumEntries = (entries == null) ? 0 : entries.length;
                        int saveFileSecureNumEntries = 0;
                        int saveFileNumEntries = 0;
                        int systemFileNumEntries = 0;

                        // List all files in the savedata (normal and/or encrypted).
                        for (int i = 0; i < maxNumEntries; i++) {
                            // File names are upper cased in some conditions
                            String entry = getMsFileName(entries[i]);
                            String filePath = path + "/" + entry;
                            SceIoStat stat = Modules.IoFileMgrForUserModule.statFile(filePath);

                            // System files.
                            if (SceUtilitySavedataParam.isSystemFile(entry)) {
                                if (systemEntriesAddr != 0 && systemFileNumEntries < systemMaxNumEntries) {
                                    int entryAddr = systemEntriesAddr + systemFileNumEntries * 80;
                                    if (stat != null) {
                                        mem.write32(entryAddr + 0, stat.mode);
                                        mem.write64(entryAddr + 8, stat.size);
                                        stat.ctime.write(mem, entryAddr + 16);
                                        stat.atime.write(mem, entryAddr + 32);
                                        stat.mtime.write(mem, entryAddr + 48);
                                    }
                                    Utilities.writeStringNZ(mem, entryAddr + 64, 16, entry);
                                    systemFileNumEntries++;
                                }
                            } else if (savedataParams.isSecureFile(entry)) {
                                // Write to secure.
                                if (saveFileSecureEntriesAddr != 0 && saveFileSecureNumEntries < saveFileSecureMaxNumEntries) {
                                    int entryAddr = saveFileSecureEntriesAddr + saveFileSecureNumEntries * 80;
                                    if (stat != null) {
                                        mem.write32(entryAddr + 0, stat.mode);
                                        // Write the file size
                                        long fileSize = stat.size;
                                        if (CryptoEngine.getSavedataCryptoStatus()) {
                                            // Write the size of the decrypted file (fileSize -= IV).
                                            fileSize -= 0x10;
                                        }
                                        mem.write64(entryAddr + 8, fileSize);
                                        stat.ctime.write(mem, entryAddr + 16);
                                        stat.atime.write(mem, entryAddr + 32);
                                        stat.mtime.write(mem, entryAddr + 48);
                                    }
                                    Utilities.writeStringNZ(mem, entryAddr + 64, 16, entry);
                                    saveFileSecureNumEntries++;
                                }
                            } else {
                                // Write to normal.
                                if (saveFileEntriesAddr != 0 && saveFileNumEntries < saveFileMaxNumEntries) {
                                    int entryAddr = saveFileEntriesAddr + saveFileNumEntries * 80;
                                    if (stat != null) {
                                        mem.write32(entryAddr + 0, stat.mode);
                                        mem.write64(entryAddr + 8, stat.size);
                                        stat.ctime.write(mem, entryAddr + 16);
                                        stat.atime.write(mem, entryAddr + 32);
                                        stat.mtime.write(mem, entryAddr + 48);
                                    }
                                    Utilities.writeStringNZ(mem, entryAddr + 64, 16, entry);
                                    saveFileNumEntries++;
                                }
                            }
                        }

                        if (entries == null) {
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                        } else {
                            savedataParams.base.result = checkMultipleCallStatus();
                        }

                        if (savedataParams.base.result == 0) {
                            // These values are only written when no error is returned
                            mem.write32(fileListAddr + 12, saveFileSecureNumEntries);
                            mem.write32(fileListAddr + 16, saveFileNumEntries);
                            mem.write32(fileListAddr + 20, systemFileNumEntries);
                        }

                        if (log.isDebugEnabled()) {
                            log.debug(String.format("FileList: %s", Utilities.getMemoryDump(fileListAddr, 36)));
                            if (saveFileSecureEntriesAddr != 0 && saveFileSecureNumEntries > 0) {
                                log.debug(String.format("SecureEntries: %s", Utilities.getMemoryDump(saveFileSecureEntriesAddr, saveFileSecureNumEntries * 80)));
                            }
                            if (saveFileEntriesAddr != 0 && saveFileNumEntries > 0) {
                                log.debug(String.format("NormalEntries: %s", Utilities.getMemoryDump(saveFileEntriesAddr, saveFileNumEntries * 80)));
                            }
                            if (systemEntriesAddr != 0 && systemFileNumEntries > 0) {
                                log.debug(String.format("SystemEntries: %s", Utilities.getMemoryDump(systemEntriesAddr, systemFileNumEntries * 80)));
                            }
                        }
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_MAKEDATA:
                case SceUtilitySavedataParam.MODE_MAKEDATASECURE: {
                    // Split saving version.
                    // Write system data files (encrypted or not).
                    try {
                        savedataParams.save(mem, savedataParams.mode == SceUtilitySavedataParam.MODE_MAKEDATASECURE);
                        savedataParams.base.result = checkMultipleCallStatus();
                    } catch (IOException e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_ACCESS_ERROR;
                    } catch (Exception e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_ACCESS_ERROR;
                        log.error(e);
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_READ:
                case SceUtilitySavedataParam.MODE_READSECURE: {
                    // Sub-types of mode LOAD.
                    // Loads data and can be called multiple times for updating.
                    if (savedataParams.saveName == null || savedataParams.saveName.length() == 0) {
                        if (savedataParams.saveNameList != null && savedataParams.saveNameList.length > 0) {
                            savedataParams.saveName = savedataParams.saveNameList[0];
                        }
                    }

                    try {
                        savedataParams.load(mem);
                        if (log.isTraceEnabled()) {
                            log.trace(String.format("MODE_READ/MODE_READSECURE reading %s", Utilities.getMemoryDump(savedataParams.dataBuf, savedataParams.dataSize, 4, 16)));
                        }
                        savedataParams.base.result = checkMultipleCallStatus();
                        savedataParams.write(mem);
                    } catch (FileNotFoundException e) {
                        if (savedataParams.isGameDirectoryPresent()) {
                            // Directory exists but file does not exist
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_FILE_NOT_FOUND;
                        } else {
                            // Directory does not exist
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                        }
                    } catch (IOException e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                    } catch (Exception e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_ACCESS_ERROR;
                        log.error(e);
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_WRITE:
                case SceUtilitySavedataParam.MODE_WRITESECURE: {
                    // Sub-types of mode SAVE.
                    // Writes data and can be called multiple times for updating.
                    try {
                        savedataParams.save(mem, savedataParams.mode == SceUtilitySavedataParam.MODE_WRITESECURE);
                        savedataParams.base.result = checkMultipleCallStatus();
                    } catch (IOException e) {
                        if (!savedataParams.isGameDirectoryPresent()) {
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                        } else {
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_ACCESS_ERROR;
                        }
                    } catch (Exception e) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_ACCESS_ERROR;
                        log.error(e);
                    }
                    break;
                }

                case SceUtilitySavedataParam.MODE_DELETEDATA:
                    // Sub-type of mode DELETE.
                    // Deletes the contents of only one specified file.
                    if (savedataParams.deleteFile(savedataParams.fileName)) {
                        savedataParams.base.result = checkMultipleCallStatus();
                    } else {
                        log.warn("Savedata MODE_DELETEDATA no data found!");
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                    }
                    break;

                case SceUtilitySavedataParam.MODE_GETSIZE:
                    int buffer6Addr = savedataParams.sizeAddr;
                    boolean isPresent = savedataParams.isPresent();

                    if (Memory.isAddressGood(buffer6Addr)) {
                        int saveFileSecureNumEntries = mem.read32(buffer6Addr + 0);
                        int saveFileNumEntries = mem.read32(buffer6Addr + 4);
                        int saveFileSecureEntriesAddr = mem.read32(buffer6Addr + 8);
                        int saveFileEntriesAddr = mem.read32(buffer6Addr + 12);

                        int totalSizeKb = 0;

                        for (int i = 0; i < saveFileSecureNumEntries; i++) {
                            int entryAddr = saveFileSecureEntriesAddr + i * 24;
                            long size = mem.read64(entryAddr);
                            String fileName = Utilities.readStringNZ(entryAddr + 8, 16);
                            int sizeKb = Utilities.getSizeKb(size);
                            if (log.isDebugEnabled()) {
                                log.debug(String.format("   Secure File '%s', size %d (%d KB)", fileName, size, sizeKb));
                            }

                            totalSizeKb += sizeKb;
                        }
                        for (int i = 0; i < saveFileNumEntries; i++) {
                            int entryAddr = saveFileEntriesAddr + i * 24;
                            long size = mem.read64(entryAddr);
                            String fileName = Utilities.readStringNZ(entryAddr + 8, 16);
                            int sizeKb = Utilities.getSizeKb(size);
                            if (log.isDebugEnabled()) {
                                log.debug(String.format("   File '%s', size %d (%d KB)", fileName, size, sizeKb));
                            }

                            totalSizeKb += sizeKb;
                        }

                        // Free MS size.
                        int freeSizeKb = MemoryStick.getFreeSizeKb();
                        String memoryStickFreeSpaceString = MemoryStick.getSizeKbString(freeSizeKb);
                        mem.write32(buffer6Addr + 16, MemoryStick.getSectorSize());
                        mem.write32(buffer6Addr + 20, freeSizeKb / MemoryStick.getSectorSizeKb());
                        mem.write32(buffer6Addr + 24, freeSizeKb);
                        Utilities.writeStringNZ(mem, buffer6Addr + 28, 8, memoryStickFreeSpaceString);

                        // If there's not enough size, we have to write how much size we need.
                        // With enough size, our needed size is always 0.
                        if (totalSizeKb > freeSizeKb) {
                            int neededSizeKb = totalSizeKb - freeSizeKb;

                            // Additional size needed to write savedata.
                            mem.write32(buffer6Addr + 36, neededSizeKb);
                            Utilities.writeStringNZ(mem, buffer6Addr + 40, 8, MemoryStick.getSizeKbString(neededSizeKb));

                            if (isPresent) {
                                // Additional size needed to overwrite savedata.
                                mem.write32(buffer6Addr + 48, neededSizeKb);
                                Utilities.writeStringNZ(mem, buffer6Addr + 52, 8, MemoryStick.getSizeKbString(neededSizeKb));
                            }
                        } else {
                            mem.write32(buffer6Addr + 36, 0);
                            if (isPresent) {
                                mem.write32(buffer6Addr + 48, 0);
                            }
                        }
                    }

                    // MODE_GETSIZE also checks if a MemoryStick is inserted and if there're no previous data.
                    if (MemoryStick.getStateMs() != MemoryStick.PSP_MEMORYSTICK_STATE_DRIVER_READY) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_MEMSTICK;
                    } else if (!isPresent) {
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                    } else {
                        savedataParams.base.result = checkMultipleCallStatus();
                    }
                    break;

                case SceUtilitySavedataParam.MODE_ERASESECURE:
                    if (savedataParams.fileName != null) {
                        String save = savedataParams.getFileName(savedataParams.saveName, savedataParams.fileName);
                        if (Modules.IoFileMgrForUserModule.deleteFile(save)) {
                            savedataParams.base.result = checkMultipleCallStatus();
                        } else if (savedataParams.isGameDirectoryPresent()) {
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                        } else {
                            savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_FILE_NOT_FOUND;
                        }
                    } else {
                        log.warn("Savedata MODE_ERASESECURE no fileName specified!");
                        savedataParams.base.result = SceKernelErrors.ERROR_SAVEDATA_RW_NO_DATA;
                    }
                    break;

                default:
                    log.warn(String.format("Savedata - Unsupported mode %d", savedataParams.mode));
                    quitDialog(-1);
                    break;
            }

            savedataParams.base.writeResult(mem);
            if (log.isDebugEnabled()) {
                log.debug(String.format("hleUtilitySavedataDisplay result: 0x%08X", savedataParams.base.result));
            }

            return false;
        }

        @Override
        protected boolean hasDialog() {
            switch (savedataParams.mode) {
                // Only these modes have a dialog with the user
                case SceUtilitySavedataParam.MODE_LOAD:
                case SceUtilitySavedataParam.MODE_SAVE:
                case SceUtilitySavedataParam.MODE_LISTLOAD:
                case SceUtilitySavedataParam.MODE_LISTSAVE:
                case SceUtilitySavedataParam.MODE_LISTDELETE:
                case SceUtilitySavedataParam.MODE_DELETE:
                case SceUtilitySavedataParam.MODE_SINGLEDELETE:
                    return true;
            }

            // The other modes are silent
            return false;
        }
    }

    protected static class MsgDialogUtilityDialogState extends UtilityDialogState {

        protected SceUtilityMsgDialogParams msgDialogParams;

        public MsgDialogUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected boolean executeUpdateVisible() {
            Memory mem = Processor.memory;

            if (!isDialogOpen()) {
                GuMsgDialog gu = new GuMsgDialog(msgDialogParams, this);
                openDialog(gu);
            } else if (!isDialogActive()) {
                // buttonPressed is only set for mode TEXT, not for mode ERROR
                if (msgDialogParams.mode == SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_MODE_TEXT) {
                    msgDialogParams.buttonPressed = getButtonPressed();
                } else if (msgDialogParams.mode == SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_MODE_ERROR) {
                    msgDialogParams.buttonPressed = SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_ESC;
                } else {
                    msgDialogParams.buttonPressed = SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_INVALID;
                }

                if (log.isDebugEnabled()) {
                    log.debug(String.format("sceUtilityMsgDialog returning buttonPressed=%d", msgDialogParams.buttonPressed));
                }
                quitDialog(0);
                msgDialogParams.write(mem);
            } else {
                updateDialog();
            }

            return false;
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            msgDialogParams = new SceUtilityMsgDialogParams();
            return msgDialogParams;
        }
    }

    protected static class OskUtilityDialogState extends UtilityDialogState {

        protected SceUtilityOskParams oskParams;
        protected OskDialog oskDialog;

        public OskUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected boolean executeUpdateVisible() {
            Memory mem = Processor.memory;

            if (!isDialogOpen()) {
                oskDialog = new OskDialog(oskParams, this);
                openDialog(oskDialog);
            } else if (!isDialogActive()) {
                if (oskDialog.buttonPressed == SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK) {
                    oskParams.oskData.result = SceUtilityOskData.PSP_UTILITY_OSK_DATA_CHANGED;
                    oskParams.oskData.outText = oskDialog.textField.getText();
                    log.info("hleUtilityOskDisplay returning '" + oskParams.oskData.outText + "'");
                } else {
                    oskParams.oskData.result = SceUtilityOskData.PSP_UTILITY_OSK_DATA_CANCELED;
                    oskParams.oskData.outText = oskDialog.textField.getText();
                    log.info("hleUtilityOskDisplay cancelled");
                }
                quitDialog(0);
                oskParams.write(mem);
            } else {
                oskDialog.checkController();
                updateDialog();
            }

            return false;
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            oskParams = new SceUtilityOskParams();
            return oskParams;
        }
    }

    protected static class GameSharingUtilityDialogState extends UtilityDialogState {

        protected SceUtilityGameSharingParams gameSharingParams;

        public GameSharingUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected boolean executeUpdateVisible() {
            // TODO to be implemented
            return false;
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            gameSharingParams = new SceUtilityGameSharingParams();
            return gameSharingParams;
        }

        @Override
        protected boolean hasDialog() {
            return false;
        }
    }

    protected static class NetconfUtilityDialogState extends UtilityDialogState {

        protected SceUtilityNetconfParams netconfParams;

        public NetconfUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected boolean executeUpdateVisible() {
            boolean keepVisible = false;

            if (netconfParams.netAction == SceUtilityNetconfParams.PSP_UTILITY_NETCONF_CONNECT_APNET
                    || netconfParams.netAction == SceUtilityNetconfParams.PSP_UTILITY_NETCONF_CONNECT_APNET_LASTUSED) {
                int state = Modules.sceNetApctlModule.hleNetApctlGetState();

                // The Netconf dialog stays visible until the network reaches
                // the state PSP_NET_APCTL_STATE_GOT_IP.
                if (state == sceNetApctl.PSP_NET_APCTL_STATE_GOT_IP) {
                    quitDialog();
                    keepVisible = false;
                } else {
                    keepVisible = true;
                    if (state == sceNetApctl.PSP_NET_APCTL_STATE_DISCONNECTED) {
                        // When connecting with infrastructure, simulate a connection
                        // using the first network configuration entry.
                        Modules.sceNetApctlModule.hleNetApctlConnect(1);
                    }
                }
            } else if (netconfParams.netAction == SceUtilityNetconfParams.PSP_UTILITY_NETCONF_CONNECT_ADHOC
                    || netconfParams.netAction == SceUtilityNetconfParams.PSP_UTILITY_NETCONF_CREATE_ADHOC
                    || netconfParams.netAction == SceUtilityNetconfParams.PSP_UTILITY_NETCONF_JOIN_ADHOC) {
                int state = Modules.sceNetAdhocctlModule.hleNetAdhocctlGetState();

                // The Netconf dialog stays visible until the network reaches
                // the state PSP_ADHOCCTL_STATE_CONNECTED.
                if (state == sceNetAdhocctl.PSP_ADHOCCTL_STATE_CONNECTED) {
                    quitDialog();
                    keepVisible = false;
                } else {
                    updateDialog();
                    keepVisible = true;
                    if (state == sceNetAdhocctl.PSP_ADHOCCTL_STATE_DISCONNECTED && netconfParams.netconfData != null) {
                        // Connect to the given group name
                        Modules.sceNetAdhocctlModule.hleNetAdhocctlConnect(netconfParams.netconfData.groupName);
                    }
                }
            }

            return keepVisible;
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            netconfParams = new SceUtilityNetconfParams();
            return netconfParams;
        }
    }

    protected static class ScreenshotUtilityDialogState extends UtilityDialogState {
        protected SceUtilityScreenshotParams screenshotParams;

        public ScreenshotUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected boolean executeUpdateVisible() {
        	if (log.isDebugEnabled()) {
        		log.debug(String.format("SceUtilityScreenshotParams %s", Utilities.getMemoryDump(paramsAddr.getAddress(), params.sizeof())));
        	}

        	if (status == PSP_UTILITY_DIALOG_STATUS_VISIBLE && (screenshotParams.isContModeAuto() || screenshotParams.isContModeFinish())) {
                status = PSP_UTILITY_DIALOG_STATUS_SCREENSHOT_UNKNOWN;
            }

            if (status == PSP_UTILITY_DIALOG_STATUS_VISIBLE && !screenshotParams.isContModeAuto() && !screenshotParams.isContModeFinish() && Memory.isAddressGood(screenshotParams.imgFrameBufAddr)) {
            	Buffer buffer = Memory.getInstance().getBuffer(screenshotParams.imgFrameBufAddr, screenshotParams.imgFrameBufWidth * screenshotParams.displayHeigth * IRenderingEngine.sizeOfTextureType[screenshotParams.imgPixelFormat]);
            	String directoyName = String.format(Settings.getInstance().getDirectoryMapping("ms0") + "PSP/SCREENSHOT/%s/", screenshotParams.screenshotID);
            	new File(directoyName).mkdirs();
            	String fileName = null;
            	String fileSuffix = screenshotParams.imgFormat == PSP_UTILITY_SCREENSHOT_FORMAT_JPEG ? "jpeg" : "png";
            	if (screenshotParams.nameRule == PSP_UTILITY_SCREENSHOT_NAMERULE_AUTONUM) {
	            	for (int fileIndex = 1; fileIndex <= 9999; fileIndex++) {
	            		fileName = String.format("%s%s_%04d.%s", directoyName, screenshotParams.fileName, fileIndex, fileSuffix);
	            		if (!new File(fileName).exists()) {
	            			break;
	            		}
	            	}
            	} else {
            		fileName = String.format("%s%s.%s", directoyName, screenshotParams.fileName, fileSuffix);
            	}
            	CaptureImage captureImage = new CaptureImage(screenshotParams.imgFrameBufAddr, 0, buffer, screenshotParams.displayWidth, screenshotParams.displayHeigth, screenshotParams.imgFrameBufWidth, screenshotParams.imgPixelFormat, false, 0, false, true, null);
            	if (screenshotParams.imgFormat == PSP_UTILITY_SCREENSHOT_FORMAT_PNG) {
            		captureImage.setFileFormat("png");
            	} else if (screenshotParams.imgFormat == PSP_UTILITY_SCREENSHOT_FORMAT_JPEG) {
            		captureImage.setFileFormat("jpg");
            	}
            	captureImage.setFileName(fileName);
            	try {
					captureImage.write();
				} catch (IOException e) {
					log.error("sceUtilityScreenshot", e);
				}
            }

            // TODO to be implemented
            return false;
        }

        protected int executeContStart(TPointer paramsAddr) {
            // Continuous mode which takes several screenshots
            // on regular intervals set by an internal counter.

            // To execute the cont mode, the screenshot utility must
            // be initialized with sceUtilityScreenshotInitStart and the startupType
            // parameter has to be PSP_UTILITY_SCREENSHOT_TYPE_CONT_AUTO, otherwise, an
            // error is returned.
            if (status != PSP_UTILITY_DIALOG_STATUS_SCREENSHOT_UNKNOWN) {
                return SceKernelErrors.ERROR_UTILITY_INVALID_STATUS;
            }

            this.paramsAddr = paramsAddr;
            this.params = createParams();
            params.read(paramsAddr);
            if (log.isInfoEnabled()) {
                log.info(String.format("%sContStart %s", name, params.toString()));
            }

            if (!screenshotParams.isContModeAuto()) {
                return SceKernelErrors.ERROR_SCREENSHOT_CONT_MODE_NOT_INIT;
            }

            // PSP is moving to status QUIT
            status = PSP_UTILITY_DIALOG_STATUS_QUIT;

            return 0;
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            screenshotParams = new SceUtilityScreenshotParams();
            return screenshotParams;
        }

        @Override
        protected boolean hasDialog() {
            return false;
        }
    }

    protected static class GamedataInstallUtilityDialogState extends UtilityDialogState {

        protected SceUtilityGamedataInstallParams gamedataInstallParams;

        public GamedataInstallUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            gamedataInstallParams = new SceUtilityGamedataInstallParams();
            return gamedataInstallParams;
        }

        @Override
        protected boolean executeUpdateVisible() {
            IoFileMgrForUser fileMgr = Modules.IoFileMgrForUserModule;
            StringBuilder sourceLocalFileName = new StringBuilder();
            IVirtualFileSystem ivfs = fileMgr.getVirtualFileSystem("disc0:/PSP_GAME/INSDIR", sourceLocalFileName);
            if (ivfs != null) {
                String[] fileNames = ivfs.ioDopen(sourceLocalFileName.toString());
                if (fileNames != null) {
                    ivfs.ioDclose(sourceLocalFileName.toString());

                    StringBuilder destinationLocalFileName = new StringBuilder();
                    IVirtualFileSystem ovfs = fileMgr.getVirtualFileSystem(String.format("%s%s%s", SceUtilitySavedataParam.savedataPath, gamedataInstallParams.gameName, gamedataInstallParams.dataName), destinationLocalFileName);
                    if (ovfs != null) {
                        int numberFiles = 0;
                        for (int i = 0; i < fileNames.length; i++) {
                            String fileName = fileNames[i];
                            // Skip iso special files
                            if (!fileName.equals(".") && !fileName.equals("\01") && !fileName.equals("..")) {
                                String sourceFileName = String.format("%s/%s", sourceLocalFileName.toString(), fileName);
                                IVirtualFile ivf = ivfs.ioOpen(sourceFileName, IoFileMgrForUser.PSP_O_RDONLY, 0);
                                if (ivf != null) {
                                    String destinationFileName = String.format("%s/%s", destinationLocalFileName.toString(), fileName);
                                    IVirtualFile ovf = ovfs.ioOpen(destinationFileName, IoFileMgrForUser.PSP_O_WRONLY | IoFileMgrForUser.PSP_O_CREAT, 0777);
                                    if (ovf != null) {
                                        if (log.isDebugEnabled()) {
                                            log.debug(String.format("GamedataInstall: copying file disc0:/%s to ms0:/%s", sourceFileName, destinationFileName));
                                        }
                                        byte[] buffer = new byte[512 * 1024];
                                        long restLength = ivf.length();
                                        while (restLength > 0) {
                                            int length = buffer.length;
                                            if (length > restLength) {
                                                length = (int) restLength;
                                            }
                                            length = ivf.ioRead(buffer, 0, length);
                                            ovf.ioWrite(buffer, 0, length);

                                            restLength -= length;
                                        }
                                        ovf.ioClose();
                                        numberFiles++;
                                    }
                                    ivf.ioClose();
                                }
                            }
                        }
                        // TODO Not sure about the values to return here
                        gamedataInstallParams.unkResult1 = numberFiles;
                        gamedataInstallParams.unkResult2 = numberFiles;
                        gamedataInstallParams.write(paramsAddr);
                    }
                }
            }

            return false;
        }

        @Override
        protected boolean hasDialog() {
            return false;
        }

		@Override
		protected int getShutdownDelay() {
			return 50000;
		}
    }

    protected static class NpSigninUtilityDialogState extends UtilityDialogState {

        protected SceUtilityNpSigninParams npSigninParams;

        public NpSigninUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            npSigninParams = new SceUtilityNpSigninParams();
            return npSigninParams;
        }

        @Override
        protected boolean executeUpdateVisible() {
            Memory mem = Processor.memory;

            npSigninParams.signinStatus = SceUtilityNpSigninParams.NP_SIGNING_STATUS_OK;
            npSigninParams.write(mem);

            int sceNp_E24DA399 = NIDMapper.getInstance().getAddressByName("sceNp_E24DA399");
            if (sceNp_E24DA399 != 0) {
            	int address = mem.read16(sceNp_E24DA399 + 0) << 16;
            	address += (short) mem.read16(sceNp_E24DA399 + 8);
            	if (Memory.isAddressGood(address)) {
            		if (log.isDebugEnabled()) {
            			log.debug(String.format("sceNp_E24DA399 Address 0x%08X", address));
            		}
            		mem.write32(address, 1);
            	}
            }

            int sceNp_C48F2847 = NIDMapper.getInstance().getAddressByName("sceNp_C48F2847");
            if (sceNp_C48F2847 != 0) {
            	int address = mem.read16(sceNp_C48F2847 + 0x74) << 16;
            	address += (short) mem.read16(sceNp_C48F2847 + 0x78);
            	if (Memory.isAddressGood(address)) {
            		if (log.isDebugEnabled()) {
            			log.debug(String.format("sceNp_C48F2847 Address 0x%08X", address));
            		}
            		Utilities.writeStringZ(mem, address, Modules.sceNpModule.getOnlineId());
            	}
            }

            int sceNpService_7EF4312E = NIDMapper.getInstance().getAddressByName("sceNpService_7EF4312E");
            if (sceNpService_7EF4312E != 0) {
            	int subAddress = (mem.read32(sceNpService_7EF4312E + 0x78) & 0x3FFFFFF) << 2;
            	int address = mem.read16(subAddress + 0x14) << 16;
            	address += (short) mem.read16(subAddress + 0x38);
            	if (Memory.isAddressGood(address)) {
            		if (log.isDebugEnabled()) {
            			log.debug(String.format("sceNpService_7EF4312E Address 0x%08X", address));
            		}
            		SysMemInfo memInfo = Modules.SysMemUserForUserModule.malloc(SysMemUserForUser.KERNEL_PARTITION_ID, "sceNpService_7EF4312E", SysMemUserForUser.PSP_SMEM_Low, 100, 0);
            		mem.write32(address, memInfo.addr);
            		mem.memset(memInfo.addr, (byte) 0, 100);
            		Utilities.writeStringZ(mem, memInfo.addr + 12, Modules.sceNpModule.getOnlineId());
            		Modules.SysMemForKernelModule.SysMemUserForUser_945E45DA(new TPointer(mem, memInfo.addr + 64));
            	}
            }

            int sceNp_02CA8CAA = NIDMapper.getInstance().getAddressByName("sceNp_02CA8CAA");
            if (sceNp_02CA8CAA != 0) {
            	int address = mem.read16(sceNp_02CA8CAA + 0x8C) << 16;
            	address += (short) mem.read16(sceNp_02CA8CAA + 0x90);
            	if (Memory.isAddressGood(address)) {
            		if (log.isDebugEnabled()) {
            			log.debug(String.format("sceNp_02CA8CAA Address 0x%08X", address));
            		}
            		mem.write64(address, sceRtc.hleGetCurrentTick());
            	}
            }

            return false;
        }

        @Override
        protected boolean hasDialog() {
            return false;
        }
    }

    protected static class HtmlViewerUtilityDialogState extends UtilityDialogState {

        protected SceUtilityHtmlViewerParams htmlViewerParams;

        public HtmlViewerUtilityDialogState(String name) {
            super(name);
        }

        @Override
        protected boolean executeUpdateVisible() {
            // TODO to be implemented
            return false;
        }

        @Override
        protected pspUtilityBaseDialog createParams() {
            htmlViewerParams = new SceUtilityHtmlViewerParams();
            return htmlViewerParams;
        }

        @Override
        protected boolean hasDialog() {
            return false;
        }
    }

    protected static abstract class UtilityDialog extends JComponent {

        private static final long serialVersionUID = -993546461292372048L;
        protected JDialog dialog;
        protected int buttonPressed;
        protected JPanel messagePane;
        protected JPanel buttonPane;
        protected ActionListener closeActionListener;
        protected static final String actionCommandOK = "OK";
        protected static final String actionCommandYES = "YES";
        protected static final String actionCommandNO = "NO";
        protected static final String actionCommandESC = "ESC";
        protected UtilityDialogState utilityDialogState;
        protected String confirmButtonActionCommand = actionCommandOK;
        protected String cancelButtonActionCommand = actionCommandESC;
        protected long pressedTimestamp;
        protected static final int repeatDelay = 200000;
        protected boolean downPressedButton;
        protected boolean downPressedAnalog;
        protected boolean upPressedButton;
        protected boolean upPressedAnalog;

        protected void createDialog(final UtilityDialogState utilityDialogState, String message) {
            this.utilityDialogState = utilityDialogState;

            String title = String.format("Message from %s", State.title);
            dialog = new JDialog((Frame) null, title, false);
            dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            messagePane = new JPanel();
            messagePane.setBorder(new EmptyBorder(5, 10, 5, 10));
            messagePane.setLayout(new BoxLayout(messagePane, BoxLayout.Y_AXIS));

            if (message != null) {
                message = formatMessageForDialog(message);
                // Split the message according to the new lines
                while (message.length() > 0) {
                    int newLinePosition = message.indexOf("\n");
                    JLabel label = new JLabel();
                    label.setHorizontalAlignment(JLabel.CENTER);
                    label.setAlignmentX(CENTER_ALIGNMENT);
                    if (newLinePosition < 0) {
                        label.setText(message);
                        message = "";
                    } else {
                        String messagePart = message.substring(0, newLinePosition);
                        label.setText(messagePart);
                        message = message.substring(newLinePosition + 1);
                    }
                    messagePane.add(label);
                }
            }

            if (JDialog.isDefaultLookAndFeelDecorated()) {
                if (UIManager.getLookAndFeel().getSupportsWindowDecorations()) {
                    dialog.setUndecorated(true);
                    getRootPane().setWindowDecorationStyle(JRootPane.INFORMATION_DIALOG);
                }
            }

            buttonPane = new JPanel();
            buttonPane.setBorder(new EmptyBorder(5, 10, 5, 10));
            buttonPane.setLayout(new BoxLayout(buttonPane, BoxLayout.X_AXIS));

            closeActionListener = new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    processActionCommand(event.getActionCommand());
                    dispose();
                }
            };
        }

        protected void dispose() {
            dialog.dispose();
            Emulator.getMainGUI().endWindowDialog();
        }

        protected void processActionCommand(String actionCommand) {
            if (actionCommandYES.equals(actionCommand)) {
                buttonPressed = SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_YES;
            } else if (actionCommandNO.equals(actionCommand)) {
                buttonPressed = SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_NO;
            } else if (actionCommandOK.equals(actionCommand)) {
                buttonPressed = SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK;
            } else if (actionCommandESC.equals(actionCommand)) {
                buttonPressed = SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_ESC;
            } else {
                buttonPressed = SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_INVALID;
            }
        }

        protected void endDialog() {
            Container contentPane = dialog.getContentPane();
            contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
            contentPane.add(messagePane);
            contentPane.add(buttonPane);

            dialog.pack();

            Emulator.getMainGUI().startWindowDialog(dialog);
        }

        protected void setDefaultButton(JButton button) {
            dialog.getRootPane().setDefaultButton(button);
        }

        @Override
        public void setVisible(boolean flag) {
            dialog.setVisible(flag);
        }

        @Override
        public boolean isVisible() {
            return dialog.isVisible();
        }

        @Override
        public Point getLocation() {
            return dialog.getLocation();
        }

        @Override
        public Dimension getSize() {
            return dialog.getSize();
        }

        protected boolean isButtonPressed(int button) {
            Controller controller = State.controller;
            if ((controller.getButtons() & button) == button) {
                return true;
            }

            return false;
        }

        protected boolean isConfirmButtonPressed() {
            return isButtonPressed(getSystemParamButtonPreference() == PSP_SYSTEMPARAM_BUTTON_CIRCLE ? sceCtrl.PSP_CTRL_CIRCLE : sceCtrl.PSP_CTRL_CROSS);
        }

        protected boolean isCancelButtonPressed() {
            return isButtonPressed(getSystemParamButtonPreference() == PSP_SYSTEMPARAM_BUTTON_CIRCLE ? sceCtrl.PSP_CTRL_CROSS : sceCtrl.PSP_CTRL_CIRCLE);
        }

        private int getControllerLy() {
            return State.controller.getLy() & 0xFF;
        }

        private int getControllerAnalogCenter() {
            return Controller.analogCenter & 0xFF;
        }

        private void checkRepeat() {
            if (pressedTimestamp != 0 && SystemTimeManager.getSystemTime() - pressedTimestamp > repeatDelay) {
                upPressedAnalog = false;
                upPressedButton = false;
                downPressedAnalog = false;
                downPressedButton = false;
                pressedTimestamp = 0;
            }
        }

        protected boolean isUpPressed() {
            checkRepeat();
            if (upPressedButton || upPressedAnalog) {
                if (!isButtonPressed(sceCtrl.PSP_CTRL_UP)) {
                    upPressedButton = false;
                }

                if (getControllerLy() >= getControllerAnalogCenter()) {
                    upPressedAnalog = false;
                }

                return false;
            }

            if (isButtonPressed(sceCtrl.PSP_CTRL_UP)) {
                upPressedButton = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            if (getControllerLy() < getControllerAnalogCenter()) {
                upPressedAnalog = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            return false;
        }

        protected boolean isDownPressed() {
            checkRepeat();
            if (downPressedButton || downPressedAnalog) {
                if (!isButtonPressed(sceCtrl.PSP_CTRL_DOWN)) {
                    downPressedButton = false;
                }

                if (getControllerLy() <= getControllerAnalogCenter()) {
                    downPressedAnalog = false;
                }

                return false;
            }

            if (isButtonPressed(sceCtrl.PSP_CTRL_DOWN)) {
                downPressedButton = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            if (getControllerLy() > getControllerAnalogCenter()) {
                downPressedAnalog = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            return false;
        }

        public void checkController() {
            if (isConfirmButtonPressed()) {
                processActionCommand(confirmButtonActionCommand);
                dispose();
            } else if (isCancelButtonPressed()) {
                processActionCommand(cancelButtonActionCommand);
                dispose();
            }
        }
    }

    protected static abstract class GuUtilityDialog {

        protected long pressedTimestamp;
        protected static final int repeatDelay = 100000;
        protected boolean downPressedButton;
        protected boolean downPressedAnalog;
        protected boolean upPressedButton;
        protected boolean upPressedAnalog;
        protected boolean leftPressedButton;
        protected boolean leftPressedAnalog;
        protected boolean rightPressedButton;
        protected boolean rightPressedAnalog;
        protected sceGu gu;
        protected UtilityDialogState utilityDialogState;
        private int x;
        private int y;
        private int textX;
        private int textY;
        private int textWidth;
        private int textHeight;
        private int textLineHeight;
        private int textAddr;
        private SceFontInfo defaultFontInfo;
        protected static final float defaultFontScale = 0.75f;
        protected static final int baseAscender = 15;
        protected static final int defaultTextWidth = 512;
        protected static final int defaultTextHeight = 32;
        protected static final int textColor = 0xFFFFFF;
        protected static final int shadowColor = 0x000000;
        protected boolean softShadows;
        protected long startDialogMillis;
        protected int drawSpeed;
        private boolean buttonsSwapped;
        private boolean hasNoButtons;
        final protected Locale utilityLocale;
        final private String strEnter;
        final private String strBack;
        final private String strYes;
        final private String strNo;

        protected GuUtilityDialog(pspUtilityDialogCommon utilityDialogCommon) {
        	utilityLocale = getUtilityLocale(utilityDialogCommon.language);
            buttonsSwapped = (utilityDialogCommon.buttonSwap == pspUtilityDialogCommon.BUTTON_ACCEPT_CIRCLE);
            hasNoButtons = false;
            strEnter = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strEnter.text");
            strBack = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strBack.text");
            strYes = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strYes.text");
            strNo = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strNo.text");
        }

        protected void createDialog(final UtilityDialogState utilityDialogState) {
            this.utilityDialogState = utilityDialogState;
            if (log.isDebugEnabled()) {
            	int partitionId = SysMemUserForUser.USER_PARTITION_ID;
                log.debug(String.format("Free memory total=0x%X, max=0x%X", Modules.SysMemUserForUserModule.totalFreeMemSize(partitionId), Modules.SysMemUserForUserModule.maxFreeMemSize(partitionId)));
            }

            startDialogMillis = Emulator.getClock().milliTime();

            // Allocate 1 MB
            gu = new sceGu(1 * 1024 * 1024);
        }

        protected void dispose() {
            if (gu != null) {
                gu.free();
                gu = null;
            }
        }

        public boolean isVisible() {
            return gu != null;
        }

        protected void update(int drawSpeed) {
            this.drawSpeed = drawSpeed;

            // Do not overwrite a sceGu list still in drawing state
            if (gu != null && !gu.isListDrawing()) {
                gu.sceGuStart();

                // Disable all common flags
                gu.sceGuDisable(IRenderingEngine.GU_DEPTH_TEST);
                gu.sceGuDisable(IRenderingEngine.GU_ALPHA_TEST);
                gu.sceGuDisable(IRenderingEngine.GU_FOG);
                gu.sceGuDisable(IRenderingEngine.GU_LIGHTING);
                gu.sceGuDisable(IRenderingEngine.GU_COLOR_LOGIC_OP);
                gu.sceGuDisable(IRenderingEngine.GU_STENCIL_TEST);
                gu.sceGuDisable(IRenderingEngine.GU_CULL_FACE);
                gu.sceGuDisable(IRenderingEngine.GU_SCISSOR_TEST);

                // Enable standard alpha blending
                gu.sceGuBlendFunc(ALPHA_SOURCE_BLEND_OPERATION_ADD, ALPHA_SOURCE_ALPHA, ALPHA_ONE_MINUS_SOURCE_ALPHA, 0, 0);
                gu.sceGuEnable(IRenderingEngine.GU_BLEND);

                updateDialog();

                gu.sceGuFinish();
            }

            checkController();
        }

        protected String truncateText(String text, int width, float scale) {
        	String truncatedText = text;
        	String truncation = "";
        	while (true) {
            	int textLength = getTextLength(getDefaultFontInfo(), truncatedText + truncation, scale);
            	if (textLength <= width) {
            		break;
            	}
        		truncatedText = truncatedText.substring(0, truncatedText.length() - 1);
        		truncation = "...";
        	}

        	return truncatedText + truncation;
        }

        private String wrapText(String text, int width, float scale) {
        	SceFontInfo fontInfo = getDefaultFontInfo();
        	int glyphType = SceFontInfo.FONT_PGF_GLYPH_TYPE_CHAR;

        	StringBuilder wrappedText = new StringBuilder();
        	int x = 0;
        	int lastSpaceStart = -1;
        	int lastSpaceEnd = -1;
        	boolean isSpace = false;
        	int scaledWidth = (int) (width / scale);
            for (int i = 0; i < text.length(); i++) {
            	char c = text.charAt(i);
            	pspCharInfo charInfo = fontInfo.getCharInfo(c, glyphType);

            	if (c == ' ') {
            		if (!isSpace) {
            			lastSpaceStart = i;
                		isSpace = true;
            		}
        			lastSpaceEnd = i + 1;
            	} else if (isSpace) {
            		lastSpaceEnd = i;
            		isSpace = false;
            	}

                int nextX = x + charInfo.sfp26AdvanceH >> 6;
                if (nextX > scaledWidth) {
            		x = 0;
            		if (lastSpaceStart >= 0) {
        				wrappedText.replace(lastSpaceStart, lastSpaceEnd, "\n");
            		} else {
            			wrappedText.append('\n');
            		}

            		if (!isSpace) {
            			wrappedText.append(c);
            		}
            	} else {
            		x = nextX;
                	wrappedText.append(c);
            	}
            }

            return wrappedText.toString();
        }

        private void drawText(SceFontInfo fontInfo, int baseAscender, char c, int glyphType) {
            pspCharInfo charInfo = fontInfo.getCharInfo(c, glyphType);
            if (log.isTraceEnabled()) {
                log.trace(String.format("drawText '%c'(%d), glyphType=0x%X, baseAscender=%d, position (%d,%d), %s", c, (int) c, glyphType, baseAscender, x, y, charInfo));
            }

            if (charInfo == null) {
                return;
            }

            if (c == '\n') {
                x = textX;
                y += textLineHeight;
                return;
            }

            fontInfo.printFont(textAddr, textWidth / 2, textWidth, textHeight, x - textX + charInfo.bitmapLeft, y - textY + baseAscender - charInfo.bitmapTop, 0, 0, 0, 0, textWidth, textHeight, PSP_FONT_PIXELFORMAT_4, c, ' ', glyphType, true);

            if (glyphType != SceFontInfo.FONT_PGF_GLYPH_TYPE_CHAR) {
                // Take the advanceH from the character, not from the shadow
                charInfo = fontInfo.getCharInfo(c, SceFontInfo.FONT_PGF_GLYPH_TYPE_CHAR);
            }
            x += charInfo.sfp26AdvanceH >> 6;
        }

        protected int getTextLines(String s) {
        	int lines = 1;
        	int index = 0;
        	while (index < s.length()) {
        		int newLine = s.indexOf("\n", index);
        		if (newLine < 0) {
        			break;
        		}
        		lines++;
        		index = newLine + 1;
        	}

        	return lines;
        }

        protected int centerText(SceFontInfo fontInfo, String s, int x0, int x1, float scale) {
        	int textLength = getTextLength(fontInfo, s, scale);
        	int width = x1 - x0 + 1;
        	if (textLength >= width) {
        		return x0;
        	}
        	return x0 + (width - textLength) / 2;
        }

        protected int getTextLength(SceFontInfo fontInfo, String s, float scale) {
        	int textLength = getTextLength(fontInfo, s);
        	textLength = (int) (scale * textLength);
        	return textLength;
        }

        protected int getTextLength(SceFontInfo fontInfo, String s) {
            int length = 0;

            for (int i = 0; i < s.length(); i++) {
                length += getTextLength(fontInfo, s.charAt(i));
            }

            return length;
        }

        protected int getTextLength(SceFontInfo fontInfo, char c) {
            pspCharInfo charInfo = fontInfo.getCharInfo(c, SceFontInfo.FONT_PGF_GLYPH_TYPE_CHAR);
            if (charInfo == null) {
                return 0;
            }
            return charInfo.sfp26AdvanceH >> 6;
        }

        protected void drawTextWithShadow(int textX, int textY, float scale, String s) {
            drawTextWithShadow(textX, textY, textColor, scale, s);
        }

        protected void drawTextWithShadow(int textX, int textY, int textColor, float scale, String s) {
        	s = wrapText(s, Screen.width - textX, scale);

            int txtHeight = defaultTextHeight;
            if (s.contains("\n")) {
                txtHeight = Screen.height - textY;
            }
            drawTextWithShadow(textX, textY, defaultTextWidth, txtHeight, 20, getDefaultFontInfo(), baseAscender, textColor, shadowColor, scale, s);
        }

        protected void drawTextWithShadow(int textX, int textY, int textWidth, int textHeight, int textLineHeight, SceFontInfo fontInfo, int baseAscender, int textColor, int shadowColor, float scale, String s) {
            drawText(textX, textY, textWidth, textHeight, textLineHeight, fontInfo, baseAscender, scale, shadowColor, s, SceFontInfo.FONT_PGF_GLYPH_TYPE_SHADOW);
            drawText(textX, textY, textWidth, textHeight, textLineHeight, fontInfo, baseAscender, scale, textColor, s, SceFontInfo.FONT_PGF_GLYPH_TYPE_CHAR);
        }

        protected void setSoftShadows(boolean softShadows) {
            this.softShadows = softShadows;
        }

        protected void drawText(int textX, int textY, int textWidth, int textHeight, int textLineHeight, SceFontInfo fontInfo, int baseAscender, float scale, int color, String s, int glyphType) {
            this.textX = textX;
            this.textY = textY;
            this.textWidth = textWidth;
            this.textHeight = textHeight;
            this.textLineHeight = textLineHeight;
            x = textX;
            y = textY;

            int textSize = textWidth * textHeight / 2;
            textAddr = gu.sceGuGetMemory(textSize);
            if (textAddr == 0) {
                return;
            }

            // Clear the texture for the text
            Memory.getInstance().memset(textAddr, (byte) 0, textSize);

            for (int i = 0; i < s.length(); i++) {
                drawText(fontInfo, baseAscender, s.charAt(i), glyphType);
            }

            final int numberOfVertex = 2;
            int textVertexAddr = gu.sceGuGetMemory(10 * numberOfVertex);
            IMemoryWriter vertexWriter = MemoryWriter.getMemoryWriter(textVertexAddr, 2);
            // Texture (0,0)
            vertexWriter.writeNext(0);
            vertexWriter.writeNext(0);
            // Position
            vertexWriter.writeNext(textX);
            vertexWriter.writeNext(textY);
            vertexWriter.writeNext(0);
            // Texture (textWidth,textHeigt)
            vertexWriter.writeNext(textWidth);
            vertexWriter.writeNext(textHeight);
            // Position
            vertexWriter.writeNext(textX + (int) (textWidth * scale));
            vertexWriter.writeNext(textY + (int) (textHeight * scale));
            vertexWriter.writeNext(0);
            vertexWriter.flush();

            int clutAddr = gu.sceGuGetMemory(16 * 4);
            IMemoryWriter clutWriter = MemoryWriter.getMemoryWriter(clutAddr, 4);
            color &= 0x00FFFFFF;
            for (int i = 0; i < 16; i++) {
                int alpha = (i << 4) | i;

                // Reduce alpha by factor 2 if soft shadows are required (MsgDialog)
                if (softShadows && glyphType == SceFontInfo.FONT_PGF_GLYPH_TYPE_SHADOW) {
                    alpha >>= 1;
                }

                clutWriter.writeNext((alpha << 24) | color);
            }
            gu.sceGuClutMode(CMODE_FORMAT_32BIT_ABGR8888, 0, 0xFF, 0);
            gu.sceGuClutLoad(2, clutAddr);

            gu.sceGuTexMode(TPSM_PIXEL_STORAGE_MODE_4BIT_INDEXED, 0, false);
            gu.sceGuTexFunc(TFUNC_FRAGMENT_DOUBLE_TEXTURE_EFECT_REPLACE, true, false);
            gu.sceGuTexEnvColor(0x000000);
            gu.sceGuTexWrap(TWRAP_WRAP_MODE_CLAMP, TWRAP_WRAP_MODE_CLAMP);
            gu.sceGuTexFilter(TFLT_LINEAR, TFLT_LINEAR);
            gu.sceGuEnable(GU_TEXTURE_2D);
            gu.sceGuTexImage(0, 512, 256, textWidth, textAddr);
            gu.sceGuDrawArray(PRIM_SPRITES, (VTYPE_TRANSFORM_PIPELINE_RAW_COORD << 23) | (VTYPE_TEXTURE_FORMAT_16_BIT) | (VTYPE_POSITION_FORMAT_16_BIT << 7), numberOfVertex, 0, textVertexAddr);
        }

        protected void drawButton(int x, int y, String text, boolean selected) {
            if (selected) {
                int alpha = getAnimationIndex(0xFF);
                gu.sceGuDrawRectangle(x, y, x + text.length() * 17, y + 16, (alpha << 24) | 0xC5C8CF);
            }
            drawTextWithShadow(x + 5, y + 2, 0.8f, text);
        }

        protected abstract void updateDialog();

        public void checkController() {
            // In case the dialog has no buttons, assume the user is confirming.
            if (canConfirm() && (isConfirmButtonPressed() || hasNoButtons())) {
                utilityDialogState.setButtonPressed(getButtonPressedOK());
                dispose();
            } else if (canCancel() && isCancelButtonPressed()) {
                utilityDialogState.setButtonPressed(SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_ESC);
                dispose();
            }

            if (hasYesNo()) {
                if (isLeftPressed()) {
                    utilityDialogState.setYesSelected(true);
                } else if (isRightPressed()) {
                    utilityDialogState.setYesSelected(false);
                }
            }
        }

        protected boolean isButtonPressed(int button) {
            Controller controller = State.controller;
            if ((controller.getButtons() & button) == button) {
                return true;
            }

            return false;
        }

        protected boolean isConfirmButtonPressed() {
            return isButtonPressed(areButtonsSwapped() ? sceCtrl.PSP_CTRL_CIRCLE : sceCtrl.PSP_CTRL_CROSS);
        }

        protected boolean isCancelButtonPressed() {
            return isButtonPressed(areButtonsSwapped() ? sceCtrl.PSP_CTRL_CROSS : sceCtrl.PSP_CTRL_CIRCLE);
        }

        protected void useNoButtons() {
            hasNoButtons = true;
        }

        protected boolean hasNoButtons() {
            return hasNoButtons;
        }

        private int getControllerLy() {
            return State.controller.getLy() & 0xFF;
        }

        private int getControllerLx() {
            return State.controller.getLx() & 0xFF;
        }

        private int getControllerAnalogCenter() {
            return Controller.analogCenter & 0xFF;
        }

        protected boolean canConfirm() {
            return true;
        }

        protected boolean canCancel() {
            return true;
        }

        protected boolean hasYesNo() {
            return false;
        }

        protected int getButtonPressedOK() {
            return SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_OK;
        }

        private void checkRepeat() {
            if (pressedTimestamp != 0 && SystemTimeManager.getSystemTime() - pressedTimestamp > repeatDelay) {
                upPressedAnalog = false;
                upPressedButton = false;
                downPressedAnalog = false;
                downPressedButton = false;
                leftPressedAnalog = false;
                leftPressedButton = false;
                rightPressedAnalog = false;
                rightPressedButton = false;
                pressedTimestamp = 0;
            }
        }

        protected boolean isUpPressed() {
            checkRepeat();
            if (upPressedButton || upPressedAnalog) {
                if (!isButtonPressed(sceCtrl.PSP_CTRL_UP)) {
                    upPressedButton = false;
                }

                if (getControllerLy() >= getControllerAnalogCenter()) {
                    upPressedAnalog = false;
                }

                return false;
            }

            if (isButtonPressed(sceCtrl.PSP_CTRL_UP)) {
                upPressedButton = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            if (getControllerLy() < getControllerAnalogCenter()) {
                upPressedAnalog = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            return false;
        }

        protected boolean isDownPressed() {
            checkRepeat();
            if (downPressedButton || downPressedAnalog) {
                if (!isButtonPressed(sceCtrl.PSP_CTRL_DOWN)) {
                    downPressedButton = false;
                }

                if (getControllerLy() <= getControllerAnalogCenter()) {
                    downPressedAnalog = false;
                }

                return false;
            }

            if (isButtonPressed(sceCtrl.PSP_CTRL_DOWN)) {
                downPressedButton = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            if (getControllerLy() > getControllerAnalogCenter()) {
                downPressedAnalog = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            return false;
        }

        protected boolean isLeftPressed() {
            checkRepeat();
            if (leftPressedButton || leftPressedAnalog) {
                if (!isButtonPressed(sceCtrl.PSP_CTRL_LEFT)) {
                    leftPressedButton = false;
                }

                if (getControllerLx() >= getControllerAnalogCenter()) {
                    leftPressedAnalog = false;
                }

                return false;
            }

            if (isButtonPressed(sceCtrl.PSP_CTRL_LEFT)) {
                leftPressedButton = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            if (getControllerLx() < getControllerAnalogCenter()) {
                leftPressedAnalog = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            return false;
        }

        protected boolean isRightPressed() {
            checkRepeat();
            if (rightPressedButton || rightPressedAnalog) {
                if (!isButtonPressed(sceCtrl.PSP_CTRL_RIGHT)) {
                    rightPressedButton = false;
                }

                if (getControllerLx() <= getControllerAnalogCenter()) {
                    rightPressedAnalog = false;
                }

                return false;
            }

            if (isButtonPressed(sceCtrl.PSP_CTRL_RIGHT)) {
                rightPressedButton = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            if (getControllerLx() > getControllerAnalogCenter()) {
                rightPressedAnalog = true;
                pressedTimestamp = SystemTimeManager.getSystemTime();
                return true;
            }

            return false;
        }

        protected SceFontInfo getDefaultFontInfo() {
            if (defaultFontInfo == null) {
                // Use "jpn0" font
                defaultFontInfo = Modules.sceFontModule.getFont(0).fontInfo;
            }
            return defaultFontInfo;
        }

        private String getCross() {
            return "X";
        }

        private String getCircle() {
            return "O";
        }

        protected boolean areButtonsSwapped() {
            return buttonsSwapped;
        }

        // Cross is always displayed to the left (even when the buttons are swapped)
        private int getCrossX() {
        	return 183;
        }

        // Circle is always displayed to the right (even when the buttons are swapped)
        private int getCircleX() {
        	return 260;
        }

        private int getEnterX() {
            return areButtonsSwapped() ? getCircleX() : getCrossX();
        }

        private int getBackX() {
            return areButtonsSwapped() ? getCrossX() : getCircleX();
        }

        private String getConfirmString() {
            return areButtonsSwapped() ? getCircle() : getCross();
        }

        private String getCancelString() {
            return areButtonsSwapped() ? getCross() : getCircle();
        }

        protected void drawEnter() {
            drawTextWithShadow(getEnterX(), 254, defaultFontScale, String.format("%s %s", getConfirmString(), strEnter));
        }

        protected void drawBack() {
            drawTextWithShadow(getBackX(), 254, defaultFontScale, String.format("%s %s", getCancelString(), strBack));
        }

        protected void drawEnterWithString(String str) {
            drawTextWithShadow(getEnterX(), 254, defaultFontScale, String.format("%s %s", getConfirmString(), str));
        }

        protected void drawBackWithString(String str) {
            drawTextWithShadow(getBackX(), 254, defaultFontScale, String.format("%s %s", getCancelString(), str));
        }

        protected void drawHeader(String title) {
            // Draw rectangle on the top of the screen
            gu.sceGuDrawRectangle(0, 0, Screen.width, 22, 0x80605C54);

            // Draw dialog title in top rectangle
            drawText(30, 4, 128, 32, 20, getDefaultFontInfo(), 15, 0.82f, 0xFFFFFF, title, SceFontInfo.FONT_PGF_GLYPH_TYPE_CHAR);
            // Draw filled circle just before dialog title
            drawText(9, 5, 32, 32, 20, getDefaultFontInfo(), 15, 0.65f, 0xFFFFFF, new String(Character.toChars(0x25CF)), SceFontInfo.FONT_PGF_GLYPH_TYPE_CHAR);
        }

        protected void drawYesNo(int xYes, int xNo, int y) {
            drawButton(xYes, y, strYes, utilityDialogState.isYesSelected());
            drawButton(xNo, y, strNo, utilityDialogState.isNoSelected());
        }

        protected void drawIcon(int textureAddr, int iconX, int iconY, int iconWidth, int iconHeight) {
            if (textureAddr == 0) {
                return;
            }

            int numberOfVertex = 2;
            int iconVertexAddr = gu.sceGuGetMemory(10 * numberOfVertex);
            if (iconVertexAddr == 0) {
                return;
            }
            IMemoryWriter vertexWriter = MemoryWriter.getMemoryWriter(iconVertexAddr, 2);
            // Texture
            vertexWriter.writeNext(0);
            vertexWriter.writeNext(0);
            // Position
            vertexWriter.writeNext(iconX);
            vertexWriter.writeNext(iconY);
            vertexWriter.writeNext(0);
            // Texture
            vertexWriter.writeNext(icon0Width);
            vertexWriter.writeNext(icon0Height);
            // Position
            vertexWriter.writeNext(iconX + iconWidth);
            vertexWriter.writeNext(iconY + iconHeight);
            vertexWriter.writeNext(0);
            vertexWriter.flush();

            gu.sceGuTexEnvColor(0x000000);
            gu.sceGuTexMode(icon0PixelFormat, 0, false);
            gu.sceGuTexImage(0, 256, 128, icon0BufferWidth, textureAddr);
            gu.sceGuTexFunc(TFUNC_FRAGMENT_DOUBLE_TEXTURE_EFECT_REPLACE, true, false);
            gu.sceGuTexFilter(TFLT_LINEAR, TFLT_LINEAR);
            gu.sceGuTexWrap(TWRAP_WRAP_MODE_CLAMP, TWRAP_WRAP_MODE_CLAMP);
            gu.sceGuEnable(GU_TEXTURE_2D);
            gu.sceGuDrawArray(PRIM_SPRITES, (VTYPE_TRANSFORM_PIPELINE_RAW_COORD << 23) | (VTYPE_TEXTURE_FORMAT_16_BIT) | (VTYPE_POSITION_FORMAT_16_BIT << 7), numberOfVertex, 0, iconVertexAddr);
        }

        protected int readIcon(InputStream is) {
            BufferedImage image = null;

            // Get icon image
            if (is != null) {
                try {
                    image = ImageIO.read(is);
                } catch (IOException e) {
                    log.debug("getIcon0", e);
                } catch (Exception e) {
                    // Corrupted data, just ignore.
                }
            }

            // Default icon
            if (image == null) {
                try {
                    image = ImageIO.read(getClass().getResource("/jpcsp/images/icon0.png"));
                } catch (IOException e) {
                    log.error("Cannot read default icon0.png", e);
                }
            }

            if (image == null) {
                return 0;
            }

            int bytesPerPixel = IRenderingEngine.sizeOfTextureType[icon0PixelFormat];
            int textureAddr = gu.sceGuGetMemory(icon0BufferWidth * icon0Height * bytesPerPixel);
            if (textureAddr == 0) {
                return 0;
            }

            IMemoryWriter textureWriter = MemoryWriter.getMemoryWriter(textureAddr, bytesPerPixel);
            int width = Math.min(image.getWidth(), icon0Width);
            int height = Math.min(image.getHeight(), icon0Height);
            for (int hy = 0; hy < height; hy++) {
                for (int wx = 0; wx < width; wx++) {
                    int colorARGB = image.getRGB(wx, hy);
                    int colorABGR = colorARGBtoABGR(colorARGB);
                    textureWriter.writeNext(colorABGR);
                }
                for (int wx = width; wx < icon0BufferWidth; wx++) {
                    textureWriter.writeNext(0);
                }
            }
            textureWriter.flush();

            return textureAddr;
        }

        protected int readIcon(int address) {
            InputStream iconStream = null;

            if (address != 0) {
                iconStream = new MemoryInputStream(address);
            }

            return readIcon(iconStream);
        }

        protected int getAnimationIndex(int maxIndex) {
            if (drawSpeed <= 0) {
                return maxIndex;
            }

            long now = Emulator.getClock().currentTimeMillis();
            int durationMillis = (int) (now - startDialogMillis);

            int animationIndex = durationMillis % 500 * (maxIndex + 1) / 500;
            if (((durationMillis / 500) % 2) != 0) {
                // Revert the animation index every 0.5 second
                animationIndex = maxIndex - animationIndex;
            }

            return animationIndex;
        }
    }

    protected static class GuSavedataDialogSave extends GuUtilityDialog {

        protected final SavedataUtilityDialogState savedataDialogState;
        protected final SceUtilitySavedataParam savedataParams;
        protected boolean isYesSelected;
        final private String strAskSaveData;
        final private String strAskOverwriteData;

        protected GuSavedataDialogSave(final SceUtilitySavedataParam savedataParams, final SavedataUtilityDialogState savedataDialogState) {
            super(savedataParams.base);
            this.savedataDialogState = savedataDialogState;
            this.savedataParams = savedataParams;

            strAskSaveData = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strAskSaveData.text");
            strAskOverwriteData = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strAskOverwriteData.text");

            createDialog(savedataDialogState);
        }

        @Override
        protected void updateDialog() {
            String dialogTitle = savedataDialogState.getDialogTitle(savedataParams.getModeName(), "Save", utilityLocale);
            Calendar savedTime = savedataParams.getSavedTime();

            drawIcon(readIcon(savedataParams.icon0FileData.buf), 26, 96, icon0Width, icon0Height);

        	String text = getText(dialogTitle);
        	boolean multiLines = getTextLines(text) > 1;
            if (hasYesNo()) {
            	int textX = multiLines ? 236 : centerText(getDefaultFontInfo(), text, 201, 464, defaultFontScale);
                gu.sceGuDrawHorizontalLine(201, 464, multiLines ? 87 : 97, 0xFF000000 | textColor);
                drawTextWithShadow(textX, multiLines ? 105 : 113, defaultFontScale, text);
                drawYesNo(278, 349, multiLines ? 154 : 145);
                gu.sceGuDrawHorizontalLine(201, 464, multiLines ? 184 : 174, 0xFF000000 | textColor);
            } else {
            	int textX = multiLines ? 270 : centerText(getDefaultFontInfo(), text, 201, 464, defaultFontScale);
                gu.sceGuDrawHorizontalLine(201, 464, 114, 0xFF000000 | textColor);
                drawTextWithShadow(textX, 131, defaultFontScale, text);
                gu.sceGuDrawHorizontalLine(201, 464, 157, 0xFF000000 | textColor);
            }

            drawTextWithShadow(6, 202, defaultFontScale, savedataParams.sfoParam.savedataTitle);
            if (savedTime != null) {
                drawTextWithShadow(6, 219, 0.7f, formatDateTime(savedTime));
            }
            drawTextWithShadow(6, 237, defaultFontScale, MemoryStick.getSizeKbString(savedataParams.getRequiredSizeKb()));

            if (hasEnter()) {
                drawEnter();
            }
            drawBack();

            drawHeader(dialogTitle);
        }

        protected String getText(String dialogTitle) {
            return (savedataParams.isPresent()) ? strAskOverwriteData : strAskSaveData;
        }

        protected boolean hasEnter() {
            return true;
        }

        @Override
        protected boolean hasYesNo() {
            return true;
        }
    }

    protected static class GuSavedataDialogLoad extends GuUtilityDialog {

        protected final SavedataUtilityDialogState savedataDialogState;
        protected final SceUtilitySavedataParam savedataParams;
        protected boolean isYesSelected;
        protected boolean hasYesNo;
        final private String strNoData;
        final private String strAskLoadData;

        protected GuSavedataDialogLoad(final SceUtilitySavedataParam savedataParams, final SavedataUtilityDialogState savedataDialogState) {
            super(savedataParams.base);
            this.savedataDialogState = savedataDialogState;
            this.savedataParams = savedataParams;

            hasYesNo = savedataParams.isPresent();

            strNoData = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strNoData.text");
            strAskLoadData = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strAskLoadData.text");

            createDialog(savedataDialogState);
        }

        @Override
        protected void updateDialog() {
            String dialogTitle = savedataDialogState.getDialogTitle(savedataParams.getModeName(), "Load", utilityLocale);
            Calendar savedTime = savedataParams.getSavedTime();

            drawIcon(readIcon(savedataParams.icon0FileData.buf), 26, 96, icon0Width, icon0Height);

            if (!hasYesNo()) {
                gu.sceGuDrawHorizontalLine(201, 464, 114, 0xFF000000 | textColor);
                drawTextWithShadow(270, 131, defaultFontScale, strNoData);
                gu.sceGuDrawHorizontalLine(201, 464, 157, 0xFF000000 | textColor);
            } else {
                gu.sceGuDrawHorizontalLine(201, 464, 87, 0xFF000000 | textColor);
                drawTextWithShadow(236, 105, defaultFontScale, strAskLoadData);
                drawYesNo(278, 349, 154);
                gu.sceGuDrawHorizontalLine(201, 464, 184, 0xFF000000 | textColor);

                drawTextWithShadow(6, 202, defaultFontScale, savedataParams.sfoParam.savedataTitle);
                if (savedTime != null) {
                    drawTextWithShadow(6, 219, 0.7f, formatDateTime(savedTime));
                }
                drawTextWithShadow(6, 237, defaultFontScale, MemoryStick.getSizeKbString(savedataParams.getRequiredSizeKb()));

                drawEnter();
            }
            drawBack();

            drawHeader(dialogTitle);
        }

        @Override
        protected boolean hasYesNo() {
            return hasYesNo;
        }

        @Override
        protected boolean canConfirm() {
            return hasYesNo();
        }
    }

    protected static class GuSavedataDialogCompleted extends GuUtilityDialog {

        protected final SavedataUtilityDialogState savedataDialogState;
        protected final SceUtilitySavedataParam savedataParams;
        private final InputStream icon0;
        protected boolean isYesSelected;
        private String strCompleted;

        protected GuSavedataDialogCompleted(final SceUtilitySavedataParam savedataParams, final SavedataUtilityDialogState savedataDialogState, final InputStream icon0) {
            super(savedataParams.base);
            this.savedataDialogState = savedataDialogState;
            this.savedataParams = savedataParams;
            this.icon0 = icon0;
            if (icon0 != null) {
            	icon0.mark(Integer.MAX_VALUE);
            }
            try {
            	strCompleted = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString(String.format("sceUtilitySavedata.%s.strCompleted.text", savedataParams.getModeName()));
            } catch (MissingResourceException e) {
            	// Ignore exception and provide a default
            	strCompleted = "Completed";
            }

            createDialog(savedataDialogState);
        }

        @Override
        protected void updateDialog() {
            String dialogTitle = savedataDialogState.getDialogTitle(savedataParams.getModeName(), "Save", utilityLocale);
            Calendar savedTime = savedataParams.getSavedTime();

            int textureAddr;
            // Take the icon0 from the selection in case the icon0 is not saved into the savedataParams
            if (savedataParams.icon0FileData.buf == 0 && icon0 != null) {
            	try {
					icon0.reset();
				} catch (IOException e) {
					// Ignore exception
				}

            	textureAddr = readIcon(icon0);
            } else {
            	textureAddr = readIcon(savedataParams.icon0FileData.buf);
            }
            drawIcon(textureAddr, 26, 96, icon0Width, icon0Height);

            gu.sceGuDrawHorizontalLine(201, 464, 114, 0xFF000000 | textColor);
            drawTextWithShadow(270, 131, defaultFontScale, strCompleted);
            gu.sceGuDrawHorizontalLine(201, 464, 157, 0xFF000000 | textColor);

            drawTextWithShadow(6, 202, defaultFontScale, savedataParams.sfoParam.savedataTitle);
            if (savedTime != null) {
                drawTextWithShadow(6, 219, 0.7f, formatDateTime(savedTime));
            }
            drawTextWithShadow(6, 237, defaultFontScale, MemoryStick.getSizeKbString(savedataParams.getRequiredSizeKb()));

            drawBack();

            drawHeader(dialogTitle);
        }

        @Override
        protected boolean canConfirm() {
            return false;
        }
    }

    protected static class GuSavedataDialog extends GuUtilityDialog {

        private final SavedataUtilityDialogState savedataDialogState;
        private final SceUtilitySavedataParam savedataParams;
        private final String[] saveNames;
        private final int numberRows;
        private int selectedRow;
        private String strNewData;
        final private String strNoData;

        public GuSavedataDialog(final SceUtilitySavedataParam savedataParams, final SavedataUtilityDialogState savedataDialogState, final String[] saveNames) {
            super(savedataParams.base);
            this.savedataDialogState = savedataDialogState;
            this.savedataParams = savedataParams;
            this.saveNames = saveNames;

            strNoData = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strNoData.text");

            if (savedataParams.newData != null && savedataParams.newData.title != null) {
            	// the PspUtilitySavedataListSaveNewData structure contains the title
            	// to be used for new data.
            	strNewData = savedataParams.newData.title;
            } else {
            	strNewData = ResourceBundle.getBundle("jpcsp/languages/jpcsp", utilityLocale).getString("sceUtilitySavedata.strNewData.text");
            }

            createDialog(savedataDialogState);

            numberRows = saveNames == null ? 0 : saveNames.length;
            savedataDialogState.saveListEmpty = (numberRows <= 0);

            // Define the selected row according to the focus field
            selectedRow = 0;
            switch (savedataParams.focus) {
                case SceUtilitySavedataParam.FOCUS_FIRSTLIST: {
                    selectedRow = 0;
                    break;
                }
                case SceUtilitySavedataParam.FOCUS_LASTLIST: {
                    selectedRow = numberRows - 1;
                    break;
                }
                case SceUtilitySavedataParam.FOCUS_LATEST: {
                    long latestTimestamp = Long.MIN_VALUE;
                    for (int i = 0; i < numberRows; i++) {
                        long timestamp = getTimestamp(saveNames[i]);
                        if (timestamp > latestTimestamp) {
                            latestTimestamp = timestamp;
                            selectedRow = i;
                        }
                    }
                    break;
                }
                case SceUtilitySavedataParam.FOCUS_OLDEST: {
                    long oldestTimestamp = Long.MAX_VALUE;
                    for (int i = 0; i < numberRows; i++) {
                        long timestamp = getTimestamp(saveNames[i]);
                        if (timestamp < oldestTimestamp) {
                            oldestTimestamp = timestamp;
                            selectedRow = i;
                        }
                    }
                    break;
                }
                case SceUtilitySavedataParam.FOCUS_FIRSTEMPTY: {
                    for (int i = 0; i < numberRows; i++) {
                        if (isEmpty(saveNames[i])) {
                            selectedRow = i;
                            break;
                        }
                    }
                    break;
                }
                case SceUtilitySavedataParam.FOCUS_LASTEMPTY: {
                    for (int i = numberRows - 1; i >= 0; i--) {
                        if (isEmpty(saveNames[i])) {
                            selectedRow = i;
                            break;
                        }
                    }
                    break;
                }
            }
        }

        private boolean isEmpty(String saveName) {
            return !savedataParams.isPresent(savedataParams.gameName, saveName);
        }

        private long getTimestamp(String saveName) {
            return savedataParams.getTimestamp(savedataParams.gameName, saveName);
        }

        private InputStream getIcon0InputStream(int index) {
            InputStream iconStream = null;

            if (index < 0 || index >= saveNames.length) {
                return iconStream;
            }

            // Get icon0 file
            String iconFileName = savedataParams.getFileName(saveNames[index], SceUtilitySavedataParam.icon0FileName);
            SeekableDataInput iconDataInput = Modules.IoFileMgrForUserModule.getFile(iconFileName, IoFileMgrForUser.PSP_O_RDONLY);
            if (iconDataInput != null) {
                try {
                    int length = (int) iconDataInput.length();
                    byte[] iconBuffer = new byte[length];
                    iconDataInput.readFully(iconBuffer);
                    iconDataInput.close();
                    iconStream = new ByteArrayInputStream(iconBuffer);
                } catch (IOException e) {
                    log.debug("getIcon0", e);
                }
            } else if (savedataParams.newData != null && savedataParams.newData.icon0 != null) {
            	// the PspUtilitySavedataListSaveNewData structure contains the default
            	// icon to be used for new data.
            	int addr = savedataParams.newData.icon0.buf;
            	int size = savedataParams.newData.icon0.size;
            	if (addr != 0 && size > 0) {
            		// An incorrect size for the icon0 is accepted, look for the end of the PNG
        			size = PNG.getEndOfPNG(Memory.getInstance(), addr, size);

        			byte[] iconBuffer = new byte[size];
            		IMemoryReader memoryReader = MemoryReader.getMemoryReader(addr, size, 1);
            		for (int i = 0; i < size; i++) {
            			iconBuffer[i] = (byte) memoryReader.readNext();
            		}
            		iconStream = new ByteArrayInputStream(iconBuffer);
            	}
            }

            return iconStream;
        }

        private int getIcon0(int index) {
        	InputStream iconStream = getIcon0InputStream(index);

            return readIcon(iconStream);
        }

        private PSF getPsf(int index) {
            PSF psf = null;
            if (index < 0 || index >= saveNames.length) {
                return psf;
            }

            String sfoFileName = savedataParams.getFileName(saveNames[index], SceUtilitySavedataParam.paramSfoFileName);
            SeekableDataInput sfoDataInput = Modules.IoFileMgrForUserModule.getFile(sfoFileName, IoFileMgrForUser.PSP_O_RDONLY);
            if (sfoDataInput != null) {
                try {
                    int length = (int) sfoDataInput.length();
                    byte[] sfoBuffer = new byte[length];
                    sfoDataInput.readFully(sfoBuffer);
                    sfoDataInput.close();

                    psf = new PSF();
                    psf.read(ByteBuffer.wrap(sfoBuffer));
                } catch (IOException e) {
                }
            }

            return psf;
        }

        private void drawIconByRow(int row, int iconX, int iconY, int iconWidth, int iconHeight) {
            drawIcon(getIcon0(row), iconX, iconY, iconWidth, iconHeight);
        }

        @Override
        protected void updateDialog() {
            if (numberRows > 0) {
                drawIconByRow(selectedRow, 26, 96, icon0Width, icon0Height);

                // Get values (title, detail...) from SFO file
                PSF psf = getPsf(selectedRow);
                if (psf != null) {
                    String title = psf.getString("TITLE");
                    String detail = psf.getString("SAVEDATA_DETAIL");
                    String savedataTitle = psf.getString("SAVEDATA_TITLE");
                    Calendar savedTime = savedataParams.getSavedTime(saveNames[selectedRow]);

                    int textX = 180;
                    int textY = 119;

                    drawTextWithShadow(textX, textY, 0xD1C6BA, 0.85f, truncateText(title, Screen.width - textX, 0.85f));

                    textY += 22;
                    if (savedTime != null) {
                    	String text = formatDateTime(savedTime);
                    	int sizeKb = savedataParams.getSizeKb(savedataParams.gameName, saveNames[selectedRow]);
                    	text += " " + MemoryStick.getSizeKbString(sizeKb);
                        drawTextWithShadow(textX, textY, 0.7f, text);
                    }

                    // Draw horizontal line below title
                    gu.sceGuDrawHorizontalLine(textX, Screen.width, textY - 6, 0xFF000000 | textColor);

                    textX -= 5;
                    textY += 23;
                    drawTextWithShadow(textX, textY, 0.7f, savedataTitle);

                    textY += 24;
                    drawTextWithShadow(textX, textY, 0.7f, detail);
                } else {
                    drawTextWithShadow(180, 130, defaultFontScale, strNewData);
                }

                drawEnter();
                drawBack();

                if (selectedRow > 0) {
                    drawIconByRow(selectedRow - 1, 58, 38, smallIcon0Width, smallIcon0Height);
                    if (selectedRow > 1) {
                        drawIconByRow(selectedRow - 2, 58, -5, smallIcon0Width, smallIcon0Height);
                    }
                }
                if (selectedRow < numberRows - 1) {
                    drawIconByRow(selectedRow + 1, 58, 190, smallIcon0Width, smallIcon0Height);
                    if (selectedRow < numberRows - 2) {
                        drawIconByRow(selectedRow + 2, 58, 233, smallIcon0Width, smallIcon0Height);
                    }
                }
            } else {
                gu.sceGuDrawHorizontalLine(201, 464, 114, 0xFF000000 | textColor);
                drawTextWithShadow(centerText(getDefaultFontInfo(), strNoData, 201, 464, defaultFontScale), 131, defaultFontScale, strNoData);
                gu.sceGuDrawHorizontalLine(201, 464, 157, 0xFF000000 | textColor);
                drawBack();
            }

            String dialogTitle = savedataDialogState.getDialogTitle(savedataParams.getModeName(), "Savedata List", utilityLocale);
            drawHeader(dialogTitle);
        }

        @Override
        public void checkController() {
            if (isDownPressed()) {
                // One row down
                selectedRow++;
            } else if (isUpPressed()) {
                // One row up
                selectedRow--;
            } else if (isButtonPressed(sceCtrl.PSP_CTRL_LTRIGGER)) {
                // First row
                selectedRow = 0;
            } else if (isButtonPressed(sceCtrl.PSP_CTRL_RTRIGGER)) {
                // Last row
                selectedRow = numberRows - 1;
            }

            selectedRow = max(selectedRow, 0);
            selectedRow = min(selectedRow, numberRows - 1);

            if (selectedRow >= 0) {
                savedataDialogState.saveListSelection = saveNames[selectedRow];
                savedataDialogState.saveListSelectionIcon0 = getIcon0InputStream(selectedRow);
            } else {
                savedataDialogState.saveListSelection = null;
                savedataDialogState.saveListSelectionIcon0 = null;
            }

            super.checkController();
        }

        @Override
        protected boolean canConfirm() {
            // Can only confirm if at least one row is displayed
            return numberRows > 0;
        }
    }

    protected static class GuMsgDialog extends GuUtilityDialog {

        protected SceUtilityMsgDialogParams msgDialogParams;

        public GuMsgDialog(final SceUtilityMsgDialogParams msgDialogParams, MsgDialogUtilityDialogState msgDialogState) {
            super(msgDialogParams.base);
            this.msgDialogParams = msgDialogParams;
            msgDialogState.setYesSelected(msgDialogParams.isOptionYesNoDefaultYes());

            createDialog(msgDialogState);
        }

        @Override
        protected void updateDialog() {
            // Shadows are softer in MsgDialog
            setSoftShadows(true);

            // Clear screen in light gray color. Do not clear depth and stencil values.
            gu.sceGuClear(CLEAR_COLOR_BUFFER, 0x968681);

            int buttonY = 192;
            String message = getMessage();
            if (message != null) {
                int currentLineLength = 0;
                List<String> lines = new LinkedList<String>();
                StringBuilder currentLine = new StringBuilder();
                int splitLineIndex = -1;
                int maxLineLength = 430;
                int longestLine = 0;
                SceFontInfo fontInfo = getDefaultFontInfo();
                for (int i = 0; i < message.length(); i++) {
                    char c = message.charAt(i);
                    if (c == '\n') {
                        longestLine = Math.max(longestLine, currentLineLength);
                        lines.add(currentLine.toString());
                        currentLine.setLength(0);
                        currentLineLength = 0;
                        splitLineIndex = -1;
                    } else {
                        int charLength = getTextLength(fontInfo, c);
                        if (currentLineLength + charLength > maxLineLength) {
                            if (splitLineIndex < 0) {
                                splitLineIndex = currentLine.length();
                            }
                            String line = currentLine.substring(0, splitLineIndex);
                            longestLine = Math.max(longestLine, getTextLength(fontInfo, line));
                            lines.add(line);
                            currentLine.delete(0, splitLineIndex);
                            currentLineLength = getTextLength(fontInfo, currentLine.toString());
                            splitLineIndex = -1;
                        }

                        currentLine.append(c);
                        currentLineLength += charLength;

                        if (c == ' ') {
                            splitLineIndex = currentLine.length();
                        }
                    }
                }
                if (currentLine.length() > 0) {
                    longestLine = Math.max(longestLine, currentLineLength);
                    lines.add(currentLine.toString());
                }
                final int lineHeight = 19;
                int lineCount = lines.size();
                int textHeight = lineHeight * lineCount;
                int totalHeight = textHeight + 24;
                if (msgDialogParams.isOptionYesNo() || msgDialogParams.isOptionOk()) {
                    // Add height for button(s)
                    totalHeight += 33;
                }
                int topLineY = (Screen.height - totalHeight) / 2;
                buttonY = topLineY + totalHeight - 29;

                int lineColor = 0xFFDFDAD9;
                // Draw top line
                gu.sceGuDrawHorizontalLine(60, 420, topLineY, lineColor);
                // Draw bottom line
                gu.sceGuDrawHorizontalLine(60, 420, topLineY + totalHeight, lineColor);

                final float scale = 0.79f;
                int y = topLineY + 17;
                // Center the text
                final int x = 63 + (360 - Math.round(longestLine * scale)) / 2;
                for (String line : lines) {
                    drawTextWithShadow(x, y, scale, line);
                    y += lineHeight;
                }
            }

            if (msgDialogParams.isOptionYesNo()) {
                drawYesNo(185, 255, buttonY);
            } else if (msgDialogParams.isOptionOk()) {
                drawButton(223, buttonY, "OK", true);
            }

            if ((msgDialogParams.options & SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_NORMAL) != 0 && !msgDialogParams.isOptionOk() && !msgDialogParams.isOptionYesNo()) {
                // In this case, no buttons are displayed to the user.
                // In the PSP the user waits a few seconds and the dialog closes itself.
                useNoButtons();
            } else {
                if ((msgDialogParams.options & SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_DISABLE_CANCEL) == SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_ENABLE_CANCEL) {
                    // Enter is not displayed when all options are 0
                    if (msgDialogParams.options != 0) {
                        if (msgDialogParams.enterButtonString != null) {
                            if (!msgDialogParams.enterButtonString.equals("")) {
                                drawEnterWithString(msgDialogParams.enterButtonString);
                            } else {
                                drawEnter();
                            }
                        } else {
                            drawEnter();
                        }
                    }
                    if (msgDialogParams.backButtonString != null) {
                        if (!msgDialogParams.backButtonString.equals("")) {
                            drawBackWithString(msgDialogParams.backButtonString);
                        } else {
                            drawBack();
                        }
                    } else {
                        drawBack();
                    }
                } else if ((msgDialogParams.options & SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_BUTTON_TYPE_MASK) != SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_BUTTON_TYPE_NONE) {
                    if (msgDialogParams.enterButtonString != null) {
                        if (!msgDialogParams.enterButtonString.equals("")) {
                            drawEnterWithString(msgDialogParams.enterButtonString);
                        } else {
                            drawEnter();
                        }
                    } else {
                        drawEnter();
                    }
                }
            }
        }

        protected String getMessage() {
            if (msgDialogParams.mode == SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_MODE_ERROR) {
                return String.format("Error 0x%08X", msgDialogParams.errorValue);
            }
            return msgDialogParams.message;
        }

        @Override
        protected boolean canCancel() {
            return (msgDialogParams.options & SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_DISABLE_CANCEL) == SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_ENABLE_CANCEL;
        }

        @Override
        protected boolean canConfirm() {
            return (msgDialogParams.options & SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_BUTTON_TYPE_MASK) != SceUtilityMsgDialogParams.PSP_UTILITY_MSGDIALOG_OPTION_BUTTON_TYPE_NONE;
        }

        @Override
        protected int getButtonPressedOK() {
            if (msgDialogParams.isOptionYesNo()) {
                return utilityDialogState.isYesSelected() ? SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_YES : SceUtilityMsgDialogParams.PSP_UTILITY_BUTTON_PRESSED_NO;
            }

            return super.getButtonPressedOK();
        }

        @Override
        protected boolean hasYesNo() {
            return msgDialogParams.isOptionYesNo();
        }
    }

    protected static class OskDialog extends UtilityDialog {

        private static final long serialVersionUID = 1155047781007677923L;
        protected JTextField textField;

        public OskDialog(final SceUtilityOskParams oskParams, OskUtilityDialogState oskState) {
            createDialog(oskState, oskParams.oskData.desc);

            textField = new JTextField(oskParams.oskData.inText);
            messagePane.add(textField);

            JButton okButton = new JButton("Ok");
            okButton.addActionListener(closeActionListener);
            okButton.setActionCommand(actionCommandOK);
            buttonPane.add(okButton);
            setDefaultButton(okButton);

            endDialog();
        }
    }

    public static String getSystemParamNickname() {
        return Settings.getInstance().readString(SYSTEMPARAM_SETTINGS_OPTION_NICKNAME);
    }

    public static int getSystemParamAdhocChannel() {
    	int indexedValue = Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_ADHOC_CHANNEL, 0);
    	String[] guiValues = SettingsGUI.getSysparamAdhocChannels();

    	int channel = 0;
    	// Invalid value?
    	if (guiValues == null || indexedValue < 0 || indexedValue >= guiValues.length) {
    		return channel;
    	}

    	// Auto value?
    	if (indexedValue == 0) {
    		return channel;
    	}

    	try {
    		channel = Integer.parseInt(guiValues[indexedValue]);
    	} catch (NumberFormatException e) {
    		log.error(String.format("Invalid channel settings value %d from %s", indexedValue, guiValues));
    	}

    	return channel;
    }

    public static int getSystemParamWlanPowersave() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_WLAN_POWER_SAVE, 0);
    }

    public static int getSystemParamDateFormat() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_DATE_FORMAT, PSP_SYSTEMPARAM_DATE_FORMAT_YYYYMMDD);
    }

    public static int getSystemParamTimeFormat() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_TIME_FORMAT, PSP_SYSTEMPARAM_TIME_FORMAT_24HR);
    }

    public static int getSystemParamTimeZone() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_TIME_ZONE, 0);
    }

    public static int getSystemParamDaylightSavingTime() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_DAYLIGHT_SAVING_TIME, 0);
    }

    public static int getSystemParamLanguage() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_LANGUAGE, PSP_SYSTEMPARAM_LANGUAGE_ENGLISH);
    }

    public static int getSystemParamButtonPreference() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_BUTTON_PREFERENCE, PSP_SYSTEMPARAM_BUTTON_CROSS);
    }

    public static int getSystemParamLockParentalLevel() {
        return Settings.getInstance().readInt(SYSTEMPARAM_SETTINGS_OPTION_LOCK_PARENTAL_LEVEL, 0);
    }

    protected static String getNetParamName(int id) {
        if (id == 0) {
            return "";
        }
        return String.format(dummyNetParamName, id);
    }

    protected static String formatMessageForDialog(String message) {
        StringBuilder formattedMessage = new StringBuilder();

        for (int i = 0; i < message.length();) {
            String rest = message.substring(i);
            int newLineIndex = rest.indexOf("\n");
            if (newLineIndex >= 0 && newLineIndex < maxLineLengthForDialog) {
                formattedMessage.append(rest.substring(0, newLineIndex + 1));
                i += newLineIndex + 1;
            } else if (rest.length() > maxLineLengthForDialog) {
                int lastSpace = rest.lastIndexOf(' ', maxLineLengthForDialog);
                rest = rest.substring(0, (lastSpace >= 0 ? lastSpace : maxLineLengthForDialog));
                formattedMessage.append(rest);
                i += rest.length() + 1;
                formattedMessage.append("\n");
            } else {
                formattedMessage.append(rest);
                i += rest.length();
            }
        }

        return formattedMessage.toString();
    }

    public static enum UtilityModule {
        PSP_MODULE_NET_COMMON(0x0100),
        PSP_MODULE_NET_ADHOC(0x0101),
        PSP_MODULE_NET_INET(0x0102),
        PSP_MODULE_NET_PARSEURI(0x0103),
        PSP_MODULE_NET_PARSEHTTP(0x0104),
        PSP_MODULE_NET_HTTP(0x0105),
        PSP_MODULE_NET_SSL(0x0106),
        PSP_MODULE_NET_UPNP(0x0107),
        PSP_MODULE_NET_HTTPSTORAGE(0x0108),
        PSP_MODULE_USB_PSPCM(0x0200),
        PSP_MODULE_USB_MIC(0x0201),
        PSP_MODULE_USB_CAM(0x0202),
        PSP_MODULE_USB_GPS(0x0203),
        PSP_MODULE_AV_AVCODEC(0x0300),
        PSP_MODULE_AV_SASCORE(0x0301),
        PSP_MODULE_AV_ATRAC3PLUS(0x0302),
        PSP_MODULE_AV_MPEGBASE(0x0303),
        PSP_MODULE_AV_MP3(0x0304),
        PSP_MODULE_AV_VAUDIO(0x0305),
        PSP_MODULE_AV_AAC(0x0306),
        PSP_MODULE_AV_G729(0x0307),
        PSP_MODULE_AV_MP4(0x0308),
        PSP_MODULE_NP_COMMON(0x0400),
        PSP_MODULE_NP_SERVICE(0x0401),
        PSP_MODULE_NP_MATCHING2(0x0402),
        PSP_MODULE_NP_COMMERCE2(0x0403),
        PSP_MODULE_NP_DRM(0x0500),
        PSP_MODULE_IRDA(0x0600);

        private final int id;

        private UtilityModule(int id) {
            this.id = id;
        }

        public int getID() {
            return id;
        }
    }

    private String getModuleName(int module) {
    	for (UtilityModule m : UtilityModule.values()) {
            if (m.getID() == module) {
            	return m.name();
            }
        }
        return String.format("PSP_MODULE_UNKNOWN_%X", module);
    }

	private String getAvModuleName(int module) {
    	if (module < 0 || module >= utilityAvModuleNames.length) {
    		return "PSP_AV_MODULE_UNKNOWN_" + module;
    	}
    	return utilityAvModuleNames[module];
    }

    private String getUsbModuleName(int module) {
    	if (module < 0 || module >= utilityUsbModuleNames.length) {
    		return "PSP_USB_MODULE_UNKNOWN_" + module;
    	}
    	return utilityUsbModuleNames[module];
    }

    @HLEFunction(nid = 0xC492F751, version = 150)
    public int sceUtilityGameSharingInitStart(TPointer paramsAddr) {
        return gameSharingState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0xEFC6F80F, version = 150)
    public int sceUtilityGameSharingShutdownStart() {
        return gameSharingState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x7853182D, version = 150)
    public int sceUtilityGameSharingUpdate(int drawSpeed) {
        return gameSharingState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x946963F3, version = 150)
    public int sceUtilityGameSharingGetStatus() {
        return gameSharingState.executeGetStatus();
    }

    @HLEFunction(nid = 0x3AD50AE7, version = 150)
    public int sceNetplayDialogInitStart(TPointer paramsAddr) {
        return netplayDialogState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0xBC6B6296, version = 150)
    public int sceNetplayDialogShutdownStart() {
        return netplayDialogState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x417BED54, version = 150)
    public int sceNetplayDialogUpdate(int drawSpeed) {
        return netplayDialogState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0xB6CEE597, version = 150)
    public int sceNetplayDialogGetStatus() {
        return netplayDialogState.executeGetStatus();
    }

    @HLEFunction(nid = 0x4DB1E739, version = 150)
    public int sceUtilityNetconfInitStart(TPointer paramsAddr) {
        return netconfState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0xF88155F6, version = 150)
    public int sceUtilityNetconfShutdownStart() {
        return netconfState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x91E70E35, version = 150)
    public int sceUtilityNetconfUpdate(int drawSpeed) {
        return netconfState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x6332AA39, version = 150)
    public int sceUtilityNetconfGetStatus() {
        return netconfState.executeGetStatus();
    }

    @HLEFunction(nid = 0x50C4CD57, version = 150)
    public int sceUtilitySavedataInitStart(@BufferInfo(lengthInfo=LengthInfo.variableLength, usage=Usage.inout) TPointer paramsAddr) {
        return savedataState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0x9790B33C, version = 150)
    public int sceUtilitySavedataShutdownStart() {
        return savedataState.executeShutdownStart();
    }

    @HLEFunction(nid = 0xD4B95FFB, version = 150)
    public int sceUtilitySavedataUpdate(int drawSpeed) {
        return savedataState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x8874DBE0, version = 150)
    public int sceUtilitySavedataGetStatus() {
        return savedataState.executeGetStatus();
    }

    @HLEFunction(nid = 0x2995D020, version = 150)
    public int sceUtilitySavedataErrInitStart(TPointer paramsAddr) {
        return savedataErrState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0xB62A4061, version = 150)
    public int sceUtilitySavedataErrShutdownStart() {
        return savedataErrState.executeShutdownStart();
    }

    @HLEFunction(nid = 0xED0FAD38, version = 150)
    public int sceUtilitySavedataErrUpdate(int drawSpeed) {
        return savedataErrState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x88BC7406, version = 150)
    public int sceUtilitySavedataErrGetStatus() {
        return savedataErrState.executeGetStatus();
    }

    @HLEFunction(nid = 0x2AD8E239, version = 150)
    public int sceUtilityMsgDialogInitStart(@BufferInfo(lengthInfo=LengthInfo.variableLength, usage=Usage.inout) TPointer paramsAddr) {
        return msgDialogState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0x67AF3428, version = 150)
    public int sceUtilityMsgDialogShutdownStart() {
        return msgDialogState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x95FC253B, version = 150)
    public int sceUtilityMsgDialogUpdate(int drawSpeed) {
        return msgDialogState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x9A1C91D7, version = 150)
    public int sceUtilityMsgDialogGetStatus() {
        return msgDialogState.executeGetStatus();
    }

    @HLEFunction(nid = 0xF6269B82, version = 150)
    public int sceUtilityOskInitStart(@BufferInfo(lengthInfo=LengthInfo.variableLength, usage=Usage.inout) TPointer paramsAddr) {
        return oskState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0x3DFAEBA9, version = 150)
    public int sceUtilityOskShutdownStart() {
        return oskState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x4B85C861, version = 150)
    public int sceUtilityOskUpdate(int drawSpeed) {
        return oskState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0xF3F76017, version = 150)
    public int sceUtilityOskGetStatus() {
        return oskState.executeGetStatus();
    }

    @HLEFunction(nid = 0x16D02AF0, version = 150)
    public int sceUtilityNpSigninInitStart(TPointer paramsAddr) {
        return npSigninState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0xE19C97D6, version = 150)
    public int sceUtilityNpSigninShutdownStart() {
        return npSigninState.executeShutdownStart();
    }

    @HLEFunction(nid = 0xF3FBC572, version = 150)
    public int sceUtilityNpSigninUpdate(int drawSpeed) {
        return npSigninState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x86ABDB1B, version = 150)
    public int sceUtilityNpSigninGetStatus() {
        return npSigninState.executeGetStatus();
    }

    @HLEFunction(nid = 0x42071A83, version = 150)
    public int sceUtilityPS3ScanInitStart(TPointer paramsAddr) {
        return PS3ScanState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0xD17A0573, version = 150)
    public int sceUtilityPS3ScanShutdownStart() {
        return PS3ScanState.executeShutdownStart();
    }

    @HLEFunction(nid = 0xD852CDCE, version = 150)
    public int sceUtilityPS3ScanUpdate(int drawSpeed) {
        return PS3ScanState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x89317C8F, version = 150)
    public int sceUtilityPS3ScanGetStatus() {
        return PS3ScanState.executeGetStatus();
    }

    @HLEFunction(nid = 0x81c44706, version = 150)
    public int sceUtilityRssReaderInitStart(TPointer paramsAddr) {
        return rssReaderState.executeInitStart(paramsAddr);
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0xB0FB7FF5, version = 150)
    public int sceUtilityRssReaderContStart() {
        return SceKernelErrors.ERROR_UTILITY_IS_UNKNOWN;
    }

    @HLEFunction(nid = 0xE7B778D8, version = 150)
    public int sceUtilityRssReaderShutdownStart() {
        return rssReaderState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x6F56F9CF, version = 150)
    public int sceUtilityRssReaderUpdate(int drawSpeed) {
        return rssReaderState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x8326AB05, version = 150)
    public int sceUtilityRssReaderGetStatus() {
        return rssReaderState.executeGetStatus();
    }

    @HLEFunction(nid = 0x4B0A8FE5, version = 150)
    public int sceUtilityRssSubscriberInitStart(TPointer paramsAddr) {
        return rssSubscriberState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0x06A48659, version = 150)
    public int sceUtilityRssSubscriberShutdownStart() {
        return rssSubscriberState.executeShutdownStart();
    }

    @HLEFunction(nid = 0xA084E056, version = 150)
    public int sceUtilityRssSubscriberUpdate(int drawSpeed) {
        return rssSubscriberState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0x2B96173B, version = 150)
    public int sceUtilityRssSubscriberGetStatus() {
        return rssSubscriberState.executeGetStatus();
    }

    @HLEFunction(nid = 0x0251B134, version = 150)
    public int sceUtilityScreenshotInitStart(TPointer paramsAddr) {
        return screenshotState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0x86A03A27, version = 150)
    public int sceUtilityScreenshotContStart(TPointer paramsAddr) {
        return screenshotState.executeContStart(paramsAddr);
    }

    @HLEFunction(nid = 0xF9E0008C, version = 150)
    public int sceUtilityScreenshotShutdownStart() {
        return screenshotState.executeShutdownStart();
    }

    @HLEFunction(nid = 0xAB083EA9, version = 150)
    public int sceUtilityScreenshotUpdate(int drawSpeed) {
        return screenshotState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0xD81957B7, version = 150)
    public int sceUtilityScreenshotGetStatus() {
        return screenshotState.executeGetStatus();
    }

    @HLEFunction(nid = 0xCDC3AA41, version = 150)
    public int sceUtilityHtmlViewerInitStart(TPointer paramsAddr) {
        return htmlViewerState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0xF5CE1134, version = 150)
    public int sceUtilityHtmlViewerShutdownStart() {
        return htmlViewerState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x05AFB9E4, version = 150)
    public int sceUtilityHtmlViewerUpdate(int drawSpeed) {
        return htmlViewerState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0xBDA7D894, version = 150)
    public int sceUtilityHtmlViewerGetStatus() {
        return htmlViewerState.executeGetStatus();
    }

    @HLEFunction(nid = 0x24AC31EB, version = 150)
    public int sceUtilityGamedataInstallInitStart(TPointer paramsAddr) {
        return gamedataInstallState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0x32E32DCB, version = 150)
    public int sceUtilityGamedataInstallShutdownStart() {
        return gamedataInstallState.executeShutdownStart();
    }

    @HLEFunction(nid = 0x4AECD179, version = 150)
    public int sceUtilityGamedataInstallUpdate(int drawSpeed) {
        return gamedataInstallState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0xB57E95D9, version = 150)
    public int sceUtilityGamedataInstallGetStatus() {
        return gamedataInstallState.executeGetStatus();
    }

    @HLEFunction(nid = 0x45C18506, version = 150)
    public int sceUtilitySetSystemParamInt(int id, int value) {
        switch (id) {
            case PSP_SYSTEMPARAM_ID_INT_ADHOC_CHANNEL:
                if (value != 0 && value != 1 && value != 6 && value != 11) {
                    return SceKernelErrors.ERROR_UTILITY_INVALID_ADHOC_CHANNEL;
                }
                Settings.getInstance().writeInt(SYSTEMPARAM_SETTINGS_OPTION_ADHOC_CHANNEL, value);
                break;
            case PSP_SYSTEMPARAM_ID_INT_WLAN_POWERSAVE:
                Settings.getInstance().writeInt(SYSTEMPARAM_SETTINGS_OPTION_WLAN_POWER_SAVE, value);
                break;
            default:
                // PSP can only set above int parameters
                return SceKernelErrors.ERROR_UTILITY_INVALID_SYSTEM_PARAM_ID;
        }

        return 0;
    }

    @HLELogging(level = "info")
    @HLEFunction(nid = 0x41E30674, version = 150)
    public int sceUtilitySetSystemParamString(int id, int string) {
        // Always return ERROR_UTILITY_INVALID_SYSTEM_PARAM_ID
        return SceKernelErrors.ERROR_UTILITY_INVALID_SYSTEM_PARAM_ID;
    }

    @HLEFunction(nid = 0xA5DA2406, version = 150)
    public int sceUtilityGetSystemParamInt(int id, @BufferInfo(usage=Usage.out) TPointer32 valueAddr) {
        switch (id) {
            case PSP_SYSTEMPARAM_ID_INT_ADHOC_CHANNEL:
                valueAddr.setValue(getSystemParamAdhocChannel());
                break;
            case PSP_SYSTEMPARAM_ID_INT_WLAN_POWERSAVE:
                valueAddr.setValue(getSystemParamWlanPowersave());
                break;
            case PSP_SYSTEMPARAM_ID_INT_DATE_FORMAT:
                valueAddr.setValue(getSystemParamDateFormat());
                break;
            case PSP_SYSTEMPARAM_ID_INT_TIME_FORMAT:
                valueAddr.setValue(getSystemParamTimeFormat());
                break;
            case PSP_SYSTEMPARAM_ID_INT_TIMEZONE:
                valueAddr.setValue(getSystemParamTimeZone());
                break;
            case PSP_SYSTEMPARAM_ID_INT_DAYLIGHTSAVINGS:
                valueAddr.setValue(getSystemParamDaylightSavingTime());
                break;
            case PSP_SYSTEMPARAM_ID_INT_LANGUAGE:
                valueAddr.setValue(getSystemParamLanguage());
                break;
            case PSP_SYSTEMPARAM_ID_INT_BUTTON_PREFERENCE:
                valueAddr.setValue(getSystemParamButtonPreference());
                break;
            case PSP_SYSTEMPARAM_ID_INT_LOCK_PARENTAL_LEVEL:
                // This system param ID was introduced somewhere between v5.00 (not available) and v6.20 (available)
                if (Emulator.getInstance().getFirmwareVersion() <= 500) {
                    log.warn(String.format("sceUtilityGetSystemParamInt id=%d, value_addr=%s PSP_SYSTEMPARAM_ID_INT_LOCK_PARENTAL_LEVEL not available in PSP v%d", id, valueAddr, Emulator.getInstance().getFirmwareVersion()));
                    return SceKernelErrors.ERROR_UTILITY_INVALID_SYSTEM_PARAM_ID;
                }
                valueAddr.setValue(getSystemParamLockParentalLevel());
                break;
            default:
                log.warn(String.format("sceUtilityGetSystemParamInt id=%d, valueAddr=%s invalid id", id, valueAddr));
                return SceKernelErrors.ERROR_UTILITY_INVALID_SYSTEM_PARAM_ID;
        }

        return 0;
    }

    @HLEFunction(nid = 0x34B78343, version = 150)
    public int sceUtilityGetSystemParamString(int id, TPointer strAddr, int len) {
        switch (id) {
            case PSP_SYSTEMPARAM_ID_STRING_NICKNAME:
                strAddr.setStringNZ(len, getSystemParamNickname());
                break;
            default:
                log.warn(String.format("sceUtilityGetSystemParamString id=%d, strAddr=%s, len=%d invalid id", id, strAddr, len));
                return SceKernelErrors.ERROR_UTILITY_INVALID_SYSTEM_PARAM_ID;
        }

        return 0;
    }

    /**
     * Check existence of a Net Configuration
     *
     * @param id - id of net Configuration (1 to n)
     * @return 0 on success,
     */
    @HLEFunction(nid = 0x5EEE6548, version = 150)
    public int sceUtilityCheckNetParam(int id) {
        boolean available = (id >= 0 && id <= 24);

        // We do not return too many entries as some homebrew only support a limited number of entries.
        if (id > PSP_NETPARAM_MAX_NUMBER_DUMMY_ENTRIES) {
        	available = false;
        }

        return available ? 0 : SceKernelErrors.ERROR_NETPARAM_BAD_NETCONF;
    }

    /**
     * Get Net Configuration Parameter
     *
     * @param conf - Net Configuration number (1 to n) (0 returns valid but
     * seems to be a copy of the last config requested)
     * @param param - which parameter to get
     * @param data - parameter data
     * @return 0 on success,
     */
    @HLEFunction(nid = 0x434D4B3A, version = 150)
    public int sceUtilityGetNetParam(int id, int param, TPointer data) {
        if (id < 0 || id > 24) {
            log.warn(String.format("sceUtilityGetNetParam invalid id=%d", id));
            return SceKernelErrors.ERROR_NETPARAM_BAD_NETCONF;
        }

        switch (param) {
            case PSP_NETPARAM_NAME:
                data.setStringZ(getNetParamName(id));
                break;
            case PSP_NETPARAM_SSID:
                data.setStringZ(sceNetApctl.getSSID());
                break;
            case PSP_NETPARAM_SECURE:
                // 0 is no security.
                // 1 is WEP (64bit).
                // 2 is WEP (128bit).
                // 3 is WPA.
                data.setValue32(1);
                break;
            case PSP_NETPARAM_WEPKEY:
                data.setStringZ("XXXXXXXXXXXXXXXXX");
                break;
            case PSP_NETPARAM_IS_STATIC_IP:
                // 0 is DHCP.
                // 1 is static.
                // 2 is PPPOE.
                data.setValue32(1);
                break;
            case PSP_NETPARAM_IP:
                data.setStringZ(sceNetApctl.getLocalHostIP());
                break;
            case PSP_NETPARAM_NETMASK:
                data.setStringZ(sceNetApctl.getSubnetMask());
                break;
            case PSP_NETPARAM_ROUTE:
                data.setStringZ(sceNetApctl.getGateway());
                break;
            case PSP_NETPARAM_MANUAL_DNS:
                // 0 is auto.
                // 1 is manual.
                data.setValue32(0);
                break;
            case PSP_NETPARAM_PRIMARYDNS:
                data.setStringZ(sceNetApctl.getPrimaryDNS());
                break;
            case PSP_NETPARAM_SECONDARYDNS:
                data.setStringZ(sceNetApctl.getSecondaryDNS());
                break;
            case PSP_NETPARAM_PROXY_USER:
                data.setStringZ("JPCSP"); // Faking.
                break;
            case PSP_NETPARAM_PROXY_PASS:
                data.setStringZ("JPCSP"); // Faking.
                break;
            case PSP_NETPARAM_USE_PROXY:
                // 0 is to not use proxy.
                // 1 is to use proxy.
                data.setValue32(0);
                break;
            case PSP_NETPARAM_PROXY_SERVER:
                data.setStringZ("dummy_server"); // Faking.
                break;
            case PSP_NETPARAM_PROXY_PORT:
                data.setValue32(0); // Faking.
                break;
            case PSP_NETPARAM_VERSION:
                // 0 is not used.
                // 1 is old version.
                // 2 is new version.
                data.setValue32(2);
                break;
            case PSP_NETPARAM_UNKNOWN:
                data.setValue32(0);
                break;
            case PSP_NETPARAM_8021X_AUTH_TYPE:
                // 0 is none.
                // 1 is EAP (MD5).
                data.setValue32(0);
                break;
            case PSP_NETPARAM_8021X_USER:
                data.setStringZ("JPCSP"); // Faking.
                break;
            case PSP_NETPARAM_8021X_PASS:
                data.setStringZ("JPCSP"); // Faking.
                break;
            case PSP_NETPARAM_WPA_TYPE:
                // 0 is key in hexadecimal format.
                // 1 is key in ASCII format.
                data.setValue32(0);
                break;
            case PSP_NETPARAM_WPA_KEY:
                data.setStringZ("XXXXXXXXXXXXXXXXX");
                break;
            case PSP_NETPARAM_BROWSER:
                // 0 is to not start the native browser.
                // 1 is to start the native browser.
                data.setValue32(0);
                break;
            case PSP_NETPARAM_WIFI_CONFIG:
                // 0 is no config.
                // 1 is unknown.
                // 2 is Playstation Spot.
                // 3 is unknown.
                data.setValue32(0);
                break;
            default:
                log.warn(String.format("sceUtilityGetNetParam invalid param %d", param));
                return SceKernelErrors.ERROR_NETPARAM_BAD_PARAM;
        }

        return 0;
    }

    /**
     * Get Current Net Configuration ID
     *
     * @param idAddr - Address to store the current net ID
     * @return 0 on success,
     */
    @HLEFunction(nid = 0x4FED24D8, version = 150)
    public int sceUtilityGetNetParamLatestID(TPointer32 idAddr) {
        // This function is saving the last net param ID and not
        // the number of net configurations.
    	idAddr.setValue(Modules.sceRegModule.getNetworkLatestId());

        return 0;
    }

    @HLEFunction(nid = 0x1579A159, version = 200, checkInsideInterrupt = true)
    public int sceUtilityLoadNetModule(int module) {
        String moduleName = getNetModuleName(module);
        int result = loadModules(moduleName);
        if (result == SceKernelErrors.ERROR_NET_MODULE_BAD_ID) {
            log.info(String.format("IGNORING: sceUtilityLoadNetModule(module=0x%04X) %s", module, moduleName));
        	Modules.ThreadManForUserModule.hleKernelDelayThread(ModuleMgrForUser.loadHLEModuleDelay, false);
            return 0;
        }

        log.info(String.format("sceUtilityLoadNetModule(module=0x%04X) %s loaded", module, moduleName));

        if (result >= 0) {
        	Modules.ThreadManForUserModule.hleKernelDelayThread(ModuleMgrForUser.loadHLEModuleDelay, false);
        }

        return result;
    }

    @HLEFunction(nid = 0x64D50C56, version = 200, checkInsideInterrupt = true)
    public int sceUtilityUnloadNetModule(int module) {
        String moduleName = getNetModuleName(module);
        log.info(String.format("sceUtilityUnloadNetModule(module=0x%04X) %s unloaded", module, moduleName));

        return unloadModules(moduleName);
    }

    @HLEFunction(nid = 0x1281DA8E, version = 200)
    public int sceUtilityInstallInitStart(@BufferInfo(lengthInfo=LengthInfo.variableLength, usage=Usage.inout) TPointer paramsAddr) {
        return installState.executeInitStart(paramsAddr);
    }

    @HLEFunction(nid = 0x5EF1C24A, version = 200)
    public int sceUtilityInstallShutdownStart() {
    	return installState.executeShutdownStart();
    }

    @HLEFunction(nid = 0xA03D29BA, version = 200)
    public int sceUtilityInstallUpdate(int drawSpeed) {
    	return installState.executeUpdate(drawSpeed);
    }

    @HLEFunction(nid = 0xC4700FA3, version = 200)
    public int sceUtilityInstallGetStatus() {
    	return installState.executeGetStatus();
    }

    @HLEFunction(nid = 0x4928BD96, version = 260)
	public int sceUtilityMsgDialogAbort() {
		return msgDialogState.executeAbort();
	}

    @HLEFunction(nid = 0xC629AF26, version = 270, checkInsideInterrupt = true)
    public int sceUtilityLoadAvModule(int module) {
        String moduleName = getAvModuleName(module);
        int result = loadModules(moduleName);
        if (result == SceKernelErrors.ERROR_AV_MODULE_BAD_ID) {
            log.info(String.format("IGNORING: sceUtilityLoadAvModule(module=0x%04X) %s", module, moduleName));
        	Modules.ThreadManForUserModule.hleKernelDelayThread(ModuleMgrForUser.loadHLEModuleDelay, false);
            return 0;
        }

        if (result >= 0) {
        	Modules.ThreadManForUserModule.hleKernelDelayThread(ModuleMgrForUser.loadHLEModuleDelay, false);
        }

        return result;
    }

    @HLEFunction(nid = 0xF7D8D092, version = 270, checkInsideInterrupt = true)
    public int sceUtilityUnloadAvModule(int module) {
        String moduleName = getAvModuleName(module);
        return unloadModules(moduleName);
    }

    @HLEFunction(nid = 0x0D5BC6D2, version = 270, checkInsideInterrupt = true)
    public int sceUtilityLoadUsbModule(int module) {
        String moduleName = getUsbModuleName(module);
        int result = loadModules(moduleName);
        if (result == SceKernelErrors.ERROR_AV_MODULE_BAD_ID) {
            log.info(String.format("IGNORING: sceUtilityLoadUsbModule(module=0x%04X) %s", module, moduleName));
        	Modules.ThreadManForUserModule.hleKernelDelayThread(ModuleMgrForUser.loadHLEModuleDelay, false);
            return 0;
        }

        if (result >= 0) {
        	Modules.ThreadManForUserModule.hleKernelDelayThread(ModuleMgrForUser.loadHLEModuleDelay, false);
        }

        return result;
    }

    @HLEFunction(nid = 0xF64910F0, version = 270, checkInsideInterrupt = true)
    public int sceUtilityUnloadUsbModule(int module) {
        String moduleName = getUsbModuleName(module);
        return unloadModules(moduleName);
    }

    @HLEFunction(nid = 0x2A2B3DE0, version = 303, checkInsideInterrupt = true)
    public int sceUtilityLoadModule(int module) {
    	String moduleName = getModuleName(module);
    	if (loadedModules.containsKey(moduleName) || waitingModules.contains(moduleName)) { // Module already loaded.
    		return SceKernelErrors.ERROR_MODULE_ALREADY_LOADED;
    	} else if (module == UtilityModule.PSP_MODULE_NET_HTTPSTORAGE.id && !loadedModules.containsKey(UtilityModule.PSP_MODULE_NET_HTTP.name())) {
            log.error("Library not find");
    		return SceKernelErrors.ERROR_KERNEL_LIBRARY_NOT_FOUND;
    	}

    	int currentThreadID = Modules.ThreadManForUserModule.getCurrentThreadID();

    	int result = loadModules(moduleName);

        if (result >= 0) {
        	int newCurrentThreadID = Modules.ThreadManForUserModule.getCurrentThreadID();
        	// Do not delay the current thread if a context switching has already happened,
        	// the thread is already delayed.
        	if (currentThreadID == newCurrentThreadID) {
        		Modules.ThreadManForUserModule.hleKernelDelayThread(currentThreadID, ModuleMgrForUser.loadHLEModuleDelay, false);
        	}
        }

        return result;
    }

    @HLEFunction(nid = 0xE49BFE92, version = 303, checkInsideInterrupt = true)
    public int sceUtilityUnloadModule(int module) {
    	String moduleName = getModuleName(module);
    	return unloadModules(moduleName);
    }

    @HLEFunction(nid = 0xDA97F1AA, version = 500)
	public int sceUtilityStoreCheckoutInitStart(TPointer paramsAddr) {
		return storeCheckoutState.executeInitStart(paramsAddr);
	}

	@HLEFunction(nid = 0x54A5C62F, version = 500)
	public int sceUtilityStoreCheckoutShutdownStart() {
		return storeCheckoutState.executeShutdownStart();
	}

	@HLEFunction(nid = 0xB8592D5F, version = 500)
	public int sceUtilityStoreCheckoutUpdate(int drawSpeed) {
		return storeCheckoutState.executeUpdate(drawSpeed);
	}

	@HLEFunction(nid = 0x3AAD51DC, version = 500)
	public int sceUtilityStoreCheckoutGetStatus() {
		return storeCheckoutState.executeGetStatus();
	}

	@HLEFunction(nid = 0xA7BB7C67, version = 500)
	public int sceUtilityPsnInitStart(TPointer paramsAddr) {
		return psnState.executeInitStart(paramsAddr);
	}

	@HLEFunction(nid = 0xC130D441, version = 500)
	public int sceUtilityPsnShutdownStart() {
		return psnState.executeShutdownStart();
	}

	@HLEFunction(nid = 0x0940A1B9, version = 500)
	public int sceUtilityPsnUpdate(int drawSpeed) {
		return psnState.executeUpdate(drawSpeed);
	}

	@HLEFunction(nid = 0x094198B8, version = 500)
	public int sceUtilityPsnGetStatus() {
		return psnState.executeGetStatus();
	}

	@HLEFunction(nid = 0x180F7B62, version = 600)
    public int sceUtilityGamedataInstallAbort() {
        return gamedataInstallState.executeAbort();
    }

	@HLEFunction(nid = 0xECE1D3E5, version = 150)
    public int sceUtility_ECE1D3E5_setAuthName(@StringInfo(maxLength = 64) PspString authName) {
		Modules.sceRegModule.setAuthName(authName.getString());

		return 0;
    }

	@HLEFunction(nid = 0x28D35634, version = 150)
    public int sceUtility_28D35634_getAuthName(TPointer authNameAddr) {
		String authName = Modules.sceRegModule.getAuthName();

		authNameAddr.setStringNZ(64, authName);

		if (log.isDebugEnabled()) {
			log.debug(String.format("sceUtility_28D35634_getAuthName returning '%s'", authName));
		}

		return 0;
    }

	@HLEUnimplemented
	@HLEFunction(nid = 0x70267ADF, version = 150)
    public int sceUtility_70267ADF_setAuthKey(@StringInfo(maxLength = 64) PspString authKey) {
		Modules.sceRegModule.setAuthKey(authKey.getString());

		return 0;
    }

	@HLEUnimplemented
	@HLEFunction(nid = 0xEF3582B2, version = 150)
    public int sceUtility_EF3582B2_getAuthKey(TPointer authKeyAddr) {
		String authKey = Modules.sceRegModule.getAuthKey();

		authKeyAddr.setStringNZ(64, authKey);

		if (log.isDebugEnabled()) {
			log.debug(String.format("sceUtility_EF3582B2_getAuthKey returning '%s'", authKey));
		}

		return 0;
    }

	@HLEFunction(nid = 0x67C2105B, version = 150)
    public int sceUtilityGetNetParamInternal(int id, int param, TPointer data) {
		return sceUtilityGetNetParam(id, param, data);
	}

	@HLEFunction(nid = 0x6D77B975, version = 150)
    public int sceUtilitySetNetParamLatestID(int id) {
		Modules.sceRegModule.setNetworkLatestId(id);

		return 0;
	}

	@HLEUnimplemented
	@HLEFunction(nid = 0x5DCBD3C0, version = 150)
    public int sceUtility_private_5DCBD3C0() {
		// Has no parameters
		return 0;
	}

	@HLEFunction(nid = 0x048BFC46, version = 150)
    public int sceUtility_private_048BFC46(PspString libraryName, int unknown1, @CanBeNull TPointer optionAddr) {
		String path = utilityPrivateModules.get(libraryName.getString());
		if (path == null) {
			return -1;
		}

        SceKernelLMOption lmOption = null;
        if (optionAddr.isNotNull()) {
            lmOption = new SceKernelLMOption();
            lmOption.read(optionAddr);
            if (log.isInfoEnabled()) {
            	log.info(String.format("sceUtility_private_048BFC46 options: %s", lmOption));
            }
        }

        LoadModuleContext loadModuleContext = new LoadModuleContext();
        loadModuleContext.fileName = path;
        loadModuleContext.lmOption = lmOption;
        loadModuleContext.allocMem = true;
        loadModuleContext.isSignChecked = Modules.ModuleMgrForUserModule.isSignChecked(path);

        return Modules.ModuleMgrForUserModule.hleKernelLoadModule(loadModuleContext);
	}

	@HLEFunction(nid = 0x78A2FE0C, version = 150)
    public int sceUtility_private_78A2FE0C(int uid) {
        return Modules.ModuleMgrForUserModule.hleKernelUnloadModule(uid);
	}

	@HLEUnimplemented
	@HLEFunction(nid = 0x031D0944, version = 150)
    public int sceUtility_private_031D0944(int unknown) {
		return 0;
	}

	@HLEUnimplemented
	@HLEFunction(nid = 0xB9D9C78F, version = 150)
    public int sceUtility_private_B9D9C78F(int unknown) {
		return 0;
	}

	@HLEUnimplemented
	@HLEFunction(nid = 0x61D0686E, version = 150)
    public int sceUtility_netparam_internal_61D0686E(int unknown) {
		return 0;
	}

	@HLEUnimplemented
	@HLEFunction(nid = 0x4CB183A4, version = 150)
    public int sceUtility_netparam_internal_4CB183A4(int unknown1, int unknown2) {
		return 0;
	}

	@HLEUnimplemented
	@HLEFunction(nid = 0x239F260D, version = 150)
    public int sceUtility_netparam_internal_239F260D(int type, TPointer value) {
		switch (type) {
			case 0:
				String configurationName = value.getStringZ();
				if (log.isDebugEnabled()) {
					log.debug(String.format("sceUtility_netparam_internal_239F260D configurationName='%s'", configurationName));
				}
				break;
			case 1:
				String ssid = value.getStringZ();
				if (log.isDebugEnabled()) {
					log.debug(String.format("sceUtility_netparam_internal_239F260D SSID='%s'", ssid));
				}
				break;
			case 2:
				int security = value.getValue32(); // One of PSP_NET_APCTL_INFO_SECURITY_TYPE_*
				if (log.isDebugEnabled()) {
					log.debug(String.format("sceUtility_netparam_internal_239F260D security=%d", security));
				}
				break;
			case 3:
			case 4:
			case 8:
			case 13:
			case 17:
			case 21:
			case 23:
			case 24:
			case 25:
			case 26:
			case 27:
			case 29:
			case 30:
			case 31:
				// ?
				if (log.isDebugEnabled()) {
					log.debug(String.format("sceUtility_netparam_internal_239F260D unknown value: %s", Utilities.getMemoryDump(value.getAddress(), 16)));
				}
				break;
			case 22:
				String wepKey = value.getStringZ();
				if (log.isDebugEnabled()) {
					log.debug(String.format("sceUtility_netparam_internal_239F260D wepKey='%s'", wepKey));
				}
				break;
		}

		return 0;
	}
}
