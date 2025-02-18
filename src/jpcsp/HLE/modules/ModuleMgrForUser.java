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

import static jpcsp.Allegrex.Common._a0;
import static jpcsp.Allegrex.Common._a1;
import static jpcsp.Allegrex.Common._ra;
import static jpcsp.Allegrex.Common._sp;
import static jpcsp.HLE.kernel.types.SceKernelErrors.ERROR_ERRNO_DEVICE_NOT_FOUND;
import static jpcsp.HLE.kernel.types.SceKernelErrors.ERROR_ERRNO_FILE_NOT_FOUND;
import static jpcsp.HLE.kernel.types.SceKernelErrors.ERROR_KERNEL_UNKNOWN_MODULE;
import static jpcsp.HLE.modules.SysMemUserForUser.KERNEL_PARTITION_ID;
import static jpcsp.util.HLEUtilities.ADDIU;
import static jpcsp.util.HLEUtilities.J;
import static jpcsp.util.HLEUtilities.JAL;
import static jpcsp.util.HLEUtilities.LUI;
import static jpcsp.util.HLEUtilities.LW;
import static jpcsp.util.HLEUtilities.SW;
import jpcsp.Allegrex.compiler.RuntimeContext;
import jpcsp.HLE.BufferInfo;
import jpcsp.HLE.BufferInfo.LengthInfo;
import jpcsp.HLE.BufferInfo.Usage;
import jpcsp.HLE.CanBeNull;
import jpcsp.HLE.HLEFunction;
import jpcsp.HLE.HLEModule;
import jpcsp.HLE.HLEModuleManager;
import jpcsp.HLE.HLEUnimplemented;
import jpcsp.HLE.PspString;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.TPointer32;

import java.io.IOException;
import java.nio.ByteBuffer;

import jpcsp.Emulator;
import jpcsp.Loader;
import jpcsp.Memory;
import jpcsp.MemoryMap;
import jpcsp.HLE.Modules;
import jpcsp.HLE.VFS.IVirtualFile;
import jpcsp.HLE.VFS.IVirtualFileSystem;
import jpcsp.HLE.VFS.crypto.PGDVirtualFile;
import jpcsp.HLE.kernel.Managers;
import jpcsp.HLE.kernel.types.IAction;
import jpcsp.HLE.kernel.types.SceIoStat;
import jpcsp.HLE.kernel.types.SceKernelErrors;
import jpcsp.HLE.kernel.types.SceKernelModuleInfo;
import jpcsp.HLE.kernel.types.SceKernelLMOption;
import jpcsp.HLE.kernel.types.SceKernelSMOption;
import jpcsp.HLE.kernel.types.SceKernelThreadInfo;
import jpcsp.HLE.kernel.types.SceModule;
import jpcsp.HLE.modules.SysMemUserForUser.SysMemInfo;
import jpcsp.filesystems.SeekableDataInput;
import jpcsp.filesystems.umdiso.UmdIsoFile;
import jpcsp.format.PSP;
import jpcsp.memory.IMemoryReader;
import jpcsp.memory.IMemoryWriter;
import jpcsp.memory.MemoryReader;
import jpcsp.memory.MemoryWriter;
import jpcsp.util.Utilities;

import org.apache.log4j.Logger;

public class ModuleMgrForUser extends HLEModule {
    public static Logger log = Modules.getLogger("ModuleMgrForUser");
    public static final int SCE_HEADER_LENGTH = 0x40;

    public static class LoadModuleContext {
        public String fileName;
        public String moduleName;
        public int flags;
        public int uid;
        public int buffer;
        public int bufferSize;
        public SceKernelLMOption lmOption;
        public boolean byUid;
        public boolean needModuleInfo;
        public boolean allocMem;
        public int baseAddr;
        public int basePartition;
        public SceKernelThreadInfo thread;
        public ByteBuffer moduleBuffer;
        public IVirtualFile vFile;
        public int moduleVersion;
        public int moduleElfVersion;
        public boolean isSignChecked;
        public Memory mem;

        public LoadModuleContext() {
        	mem = Emulator.getMemory();
        	basePartition = SysMemUserForUser.USER_PARTITION_ID;
        }

		@Override
		public String toString() {
			return String.format("fileName='%s', moduleName='%s'", fileName, moduleName);
		}
    }

    public static final int loadHLEModuleDelay = 50000; // 50 ms delay
    protected int startModuleHandler;
	private SysMemInfo startOptionsMem;
	private TPointer startOptions;

	@Override
	public void start() {
		startModuleHandler = 0;
		startOptionsMem = null;
		startOptions = null;

		super.start();
	}

	private class LoadModuleAction implements IAction {
		private LoadModuleContext loadModuleContext;

		public LoadModuleAction(LoadModuleContext loadModuleContext) {
			this.loadModuleContext = loadModuleContext;
		}

		@Override
		public void execute() {
			hleKernelLoadModuleNow(loadModuleContext);
		}
	}

	private int hleKernelLoadHLEModule(LoadModuleContext loadModuleContext) {
    	String fileName = loadModuleContext.fileName;
        HLEModuleManager moduleManager = HLEModuleManager.getInstance();

        // Extract the module name from the file name
        int startPrx = fileName.lastIndexOf("/");
        int endPrx = fileName.toLowerCase().indexOf(".prx");
        if (endPrx >= 0) {
        	loadModuleContext.moduleName = fileName.substring(startPrx + 1, endPrx);
        } else {
        	loadModuleContext.moduleName = fileName;
        }

    	// Retrieve the module name and devkitVersion from the file content
    	getModuleInfoFromFileContent(loadModuleContext);

        // Check if the module is not overwritten
        // by a file located under flash0 (copied from a real PSP).
        String modulePrxFileName = moduleManager.getModulePrxFileName(loadModuleContext.moduleName);
        if (modulePrxFileName != null) {
        	loadModuleContext.isSignChecked = isSignChecked(modulePrxFileName);
        	StringBuilder localFileName = new StringBuilder();
        	IVirtualFileSystem vfs = Modules.IoFileMgrForUserModule.getVirtualFileSystem(modulePrxFileName, localFileName);
        	if (vfs.ioGetstat(localFileName.toString(), new SceIoStat()) == 0) {
        		// The flash0 file is available, load it
        		loadModuleContext.fileName = modulePrxFileName;
        		if (log.isInfoEnabled()) {
        			log.info(String.format("hleKernelLoadModule('%s') file '%s' loaded", loadModuleContext.moduleName, modulePrxFileName));
        		}
        		return -1;
        	}
        }

        if (loadModuleContext.fileName != null && loadModuleContext.fileName.startsWith("flash0:")) {
        	StringBuilder localFileName = new StringBuilder();
        	IVirtualFileSystem vfs = Modules.IoFileMgrForUserModule.getVirtualFileSystem(loadModuleContext.fileName, localFileName);
        	if (vfs.ioGetstat(localFileName.toString(), new SceIoStat()) == 0) {
        		// The flash0 file is available, load it
        		return -1;
        	}
        }

        // Check if the PRX name matches an HLE module
        if (moduleManager.hasFlash0Module(loadModuleContext.moduleName)) {
        	if (log.isInfoEnabled()) {
        		log.info(String.format("hleKernelLoadModule(path='%s') HLE module %s loaded", loadModuleContext.fileName, loadModuleContext.moduleName));
        	}
	        int moduleMemoryPartition = loadModuleContext.lmOption != null && loadModuleContext.lmOption.mpidText != 0 ? loadModuleContext.lmOption.mpidText : SysMemUserForUser.USER_PARTITION_ID;
	        int moduleMemoryType = loadModuleContext.lmOption != null ? loadModuleContext.lmOption.position : SysMemUserForUser.PSP_SMEM_Low;
            return moduleManager.LoadFlash0Module(loadModuleContext.moduleName, loadModuleContext.moduleVersion, loadModuleContext.moduleElfVersion, moduleMemoryPartition, moduleMemoryType);
        }

        return -1;
    }

    private byte[] readHeader(LoadModuleContext loadModuleContext) {
    	byte[] header = new byte[SCE_HEADER_LENGTH + PSP.PSP_HEADER_SIZE];

        if (loadModuleContext.buffer != 0) {
        	Utilities.readBytes(loadModuleContext.buffer, header.length, header, 0);
        } else {
            SeekableDataInput file;
        	if (loadModuleContext.byUid) {
        		file = Modules.IoFileMgrForUserModule.getFile(loadModuleContext.uid);
        	} else {
        		file = Modules.IoFileMgrForUserModule.getFile(loadModuleContext.fileName, IoFileMgrForUser.PSP_O_RDONLY);
        	}

        	if (file == null) {
        		return null;
        	}

        	try {
            	long position = file.getFilePointer();
        		file.readFully(header);
        		file.seek(position);
    		} catch (IOException e) {
    			return null;
    		} finally {
    			if (!loadModuleContext.byUid) {
    	    		try {
    					file.close();
    				} catch (IOException e) {
    					// Ignore exception
    				}
    	    	}
    		}
        }

        return header;
    }

    private void getModuleInfoFromFileContent(LoadModuleContext loadModuleContext) {
    	// Extract the library name from the file itself
        // for files in "~SCE"/"~PSP" format.
    	byte[] header = readHeader(loadModuleContext);
    	if (header == null) {
    		return;
    	}

		try {
			ByteBuffer f = ByteBuffer.wrap(header);

			// Skip an optional "~SCE" header
			int magic = Utilities.readWord(f);
			if (magic == Loader.SCE_MAGIC) {
				f.position(SCE_HEADER_LENGTH);
			} else {
				f.position(0);
			}

			// Retrieve the library name from the "~PSP" header
			PSP psp = new PSP(f);
			if (psp.isValid()) {
				String libName = psp.getModname();
				if (libName != null && libName.length() > 0) {
					// We could extract the library name from the file
					loadModuleContext.moduleName = libName;
					if (log.isDebugEnabled()) {
						log.debug(String.format("getModuleNameFromFileContent %s", loadModuleContext));
					}
				}
				loadModuleContext.moduleVersion = psp.getDevkitVersion();
			} else {
				// Try to load the module info if this is a file in ELF format...
				SceModule moduleInfo = getModuleInfo(loadModuleContext);
				if (moduleInfo != null) {
					loadModuleContext.moduleName = moduleInfo.modname;
					loadModuleContext.moduleVersion = moduleInfo.moduleVersion;
					loadModuleContext.moduleElfVersion = moduleInfo.version;
				}
			}
		} catch (IOException e) {
			// Ignore exception
		}
    }

    public int hleKernelLoadModule(LoadModuleContext loadModuleContext) {
    	loadModuleContext.thread = Modules.ThreadManForUserModule.getCurrentThread();
    	IAction delayedLoadModule = new LoadModuleAction(loadModuleContext);

    	Modules.ThreadManForUserModule.getCurrentThread().wait.Io_id = -1;
    	Modules.ThreadManForUserModule.hleBlockCurrentThread(SceKernelThreadInfo.JPCSP_WAIT_IO);
    	Emulator.getScheduler().addAction(Emulator.getClock().microTime() + 100000, delayedLoadModule);

    	return 0;
    }

    public int hleKernelLoadAndStartModule(String name, int startPriority) {
    	return hleKernelLoadAndStartModule(name, startPriority, null);
    }

    public int hleKernelLoadAndStartModule(String name, int startPriority, IAction onModuleStartAction) {
    	return hleKernelLoadAndStartModule(name, startPriority, 0, TPointer.NULL, onModuleStartAction);
    }

    public int hleKernelLoadAndStartModule(String name, int startPriority, int argSize, TPointer argp, IAction onModuleStartAction) {
    	LoadModuleContext loadModuleContext = new LoadModuleContext();
    	loadModuleContext.fileName = name;
    	loadModuleContext.allocMem = true;
    	loadModuleContext.thread = Modules.ThreadManForUserModule.getCurrentThread();
    	loadModuleContext.isSignChecked = isSignChecked(name);

    	int moduleUid = hleKernelLoadModuleNow(loadModuleContext);

    	if (moduleUid >= 0) {
        	if (startOptionsMem == null) {
            	final int startOptionsSize = 20;
            	startOptionsMem = Modules.SysMemUserForUserModule.malloc(SysMemUserForUser.KERNEL_PARTITION_ID, "ModuleStartOptions", SysMemUserForUser.PSP_SMEM_Low, startOptionsSize, 0);
            	startOptions = new TPointer(Memory.getInstance(), startOptionsMem.addr);
            	startOptions.setValue32(startOptionsSize);
        	}

	    	SceKernelSMOption sceKernelSMOption = new SceKernelSMOption();
	    	sceKernelSMOption.mpidStack = 0;
	    	sceKernelSMOption.stackSize = 0;
	    	sceKernelSMOption.attribute = 0;
	    	sceKernelSMOption.priority = startPriority;
	    	sceKernelSMOption.write(startOptions);

	    	hleKernelStartModule(moduleUid, argSize, argp, TPointer32.NULL, startOptions, false, onModuleStartAction);
    	}

    	return moduleUid;
    }

    private int hleKernelLoadModuleNow(LoadModuleContext loadModuleContext) {
    	int result = delayedKernelLoadModule(loadModuleContext);
    	if (loadModuleContext.thread != null) {
    		loadModuleContext.thread.cpuContext._v0 = result;
    		Modules.ThreadManForUserModule.hleUnblockThread(loadModuleContext.thread.uid);
    	}

    	return result;
    }

    private ByteBuffer getModuleByteBuffer(LoadModuleContext loadModuleContext) {
    	if (loadModuleContext.moduleBuffer != null) {
    		return loadModuleContext.moduleBuffer;
    	}

    	if (loadModuleContext.buffer != 0) {
    		byte[] bytes = new byte[loadModuleContext.bufferSize];
    		Utilities.readBytes(loadModuleContext.buffer, loadModuleContext.bufferSize, bytes, 0);
    		return ByteBuffer.wrap(bytes);
    	}

    	SeekableDataInput moduleInput = Modules.IoFileMgrForUserModule.getFile(loadModuleContext.fileName, loadModuleContext.flags);
    	if (moduleInput != null) {
			try {
				byte[] moduleBytes = new byte[(int) moduleInput.length()];
	            moduleInput.readFully(moduleBytes);
	            moduleInput.close();
	            return ByteBuffer.wrap(moduleBytes);
			} catch (IOException e) {
				// Ignore exception
			}
        }

        return null;
    }

    public SceModule getModuleInfo(String name, ByteBuffer moduleBuffer, int mpidText, int mpidData, boolean isSignChecked) {
        SceModule module = null;
		try {
			module = Loader.getInstance().LoadModule(name, moduleBuffer, new TPointer(getMemory(), MemoryMap.START_USERSPACE), mpidText, mpidData, true, false, true, isSignChecked);
	        moduleBuffer.rewind();
		} catch (IOException e) {
			log.error("getModuleRequiredMemorySize", e);
		}

        return module;
    }

    private SceModule getModuleInfo(LoadModuleContext loadModuleContext) {
        int mpidText = loadModuleContext.lmOption != null && loadModuleContext.lmOption.mpidText != 0 ? loadModuleContext.lmOption.mpidText : SysMemUserForUser.USER_PARTITION_ID;
        int mpidData = loadModuleContext.lmOption != null && loadModuleContext.lmOption.mpidData != 0 ? loadModuleContext.lmOption.mpidData : SysMemUserForUser.USER_PARTITION_ID;

    	ByteBuffer moduleBuffer = getModuleByteBuffer(loadModuleContext);

    	return getModuleInfo(loadModuleContext.fileName, moduleBuffer, mpidText, mpidData, loadModuleContext.isSignChecked);
    }

    public int getModuleRequiredMemorySize(SceModule module) {
    	if (module == null) {
    		return 0;
    	}

    	return module.loadAddressHigh - module.loadAddressLow;
    }

    private int hleKernelLoadModuleFromModuleBuffer(LoadModuleContext loadModuleContext) throws IOException {
    	int result;

        int moduleBase;
        int mpidText;
        int mpidData;
        SysMemInfo moduleInfo = null;

        if (loadModuleContext.allocMem) {
	        mpidText = loadModuleContext.lmOption != null && loadModuleContext.lmOption.mpidText != 0 ? loadModuleContext.lmOption.mpidText : loadModuleContext.basePartition;
	        mpidData = loadModuleContext.lmOption != null && loadModuleContext.lmOption.mpidData != 0 ? loadModuleContext.lmOption.mpidData : loadModuleContext.basePartition;
	        final int allocType = loadModuleContext.lmOption != null ? loadModuleContext.lmOption.position : SysMemUserForUser.PSP_SMEM_Low;
	        final int moduleHeaderSize = 256;

	        // Load the module in analyze mode to find out its required memory size
	        SceModule testModule = getModuleInfo(loadModuleContext.fileName, loadModuleContext.moduleBuffer, mpidText, mpidData, loadModuleContext.isSignChecked);
	        int totalAllocSize = moduleHeaderSize + getModuleRequiredMemorySize(testModule);
	        if (log.isDebugEnabled()) {
	        	log.debug(String.format("Module '%s' requires 0x%X bytes memory in partition=%d", loadModuleContext.fileName, totalAllocSize, mpidText));
	        }

	        // Take the partition IDs from the module information, if available
	        if (loadModuleContext.lmOption == null || loadModuleContext.lmOption.mpidText == 0) {
	        	if (testModule.mpidtext != 0) {
	        		mpidText = testModule.mpidtext;
	        	}
	        }
	        if (loadModuleContext.lmOption == null || loadModuleContext.lmOption.mpidData == 0) {
	        	if (testModule.mpiddata != 0) {
	        		mpidData = testModule.mpiddata;
	        	}
	        }

	        SysMemInfo testInfo = Modules.SysMemUserForUserModule.malloc(mpidText, "ModuleMgr-TestInfo", allocType, totalAllocSize, 0);
	        if (testInfo == null) {
	            log.error(String.format("Failed module allocation of size 0x%08X for '%s' (maxFreeMemSize=0x%08X)", totalAllocSize, loadModuleContext.fileName, Modules.SysMemUserForUserModule.maxFreeMemSize(mpidText)));
	            return -1;
	        }
	        int testBase = testInfo.addr;
	        Modules.SysMemUserForUserModule.free(testInfo);

	        // Allocate the memory for the memory header itself,
	        // the space required by the module will be allocated by the Loader.
	        if (loadModuleContext.needModuleInfo) {
	        	moduleInfo = Modules.SysMemUserForUserModule.malloc(mpidText, "ModuleMgr", SysMemUserForUser.PSP_SMEM_Addr, moduleHeaderSize, testBase);
	            if (moduleInfo == null) {
	                log.error(String.format("Failed module allocation 0x%08X != null for '%s'", testBase, loadModuleContext.fileName));
	                return -1;
	            }
	            if (moduleInfo.addr != testBase) {
	                log.error(String.format("Failed module allocation 0x%08X != 0x%08X for '%s'", testBase, moduleInfo.addr, loadModuleContext.fileName));
	                return -1;
	            }
	            moduleBase = moduleInfo.addr + moduleHeaderSize;
	        } else {
	        	moduleBase = testBase;
	        }

	        if ((testModule.attribute & SceModule.PSP_MODULE_KERNEL) != 0 && testModule.mpidtext == KERNEL_PARTITION_ID) {
	        	moduleBase |= MemoryMap.START_KERNEL; // Set the address kernel flag 0x80000000
	        }
        } else {
    		moduleBase = loadModuleContext.baseAddr;
    		mpidText = loadModuleContext.basePartition;
    		mpidData = loadModuleContext.basePartition;
    	}

        // Load the module
    	SceModule module = Loader.getInstance().LoadModule(loadModuleContext.fileName, loadModuleContext.moduleBuffer, new TPointer(loadModuleContext.mem, moduleBase), mpidText, mpidData, false, loadModuleContext.allocMem, true, loadModuleContext.isSignChecked);
        module.load();

    	if ((module.fileFormat & Loader.FORMAT_ELF) != 0) {
			module.addAllocatedMemory(moduleInfo);
	        result = module.modid;
	    	if (log.isDebugEnabled()) {
	    		log.debug(String.format("hleKernelLoadModule returning uid=0x%X", result));
	    	}
    	} else if ((module.fileFormat & (Loader.FORMAT_SCE | Loader.FORMAT_PSP)) != 0) {
            // Simulate a successful loading
            log.info(String.format("hleKernelLoadModule(path='%s') encrypted module not loaded", loadModuleContext.fileName));
            SceModule fakeModule = new SceModule(true);
            fakeModule.modname = loadModuleContext.moduleName;
        	fakeModule.addAllocatedMemory(moduleInfo);
            if (moduleInfo != null) {
                fakeModule.write(Memory.getInstance(), moduleInfo.addr);
            }
            Managers.modules.addModule(fakeModule);
            result = fakeModule.modid;
        } else {
            // The Loader class now manages the module's memory footprint, it won't allocate if it failed to load
            result = -1;
        }

        return result;
    }

    private int delayedKernelLoadModule(LoadModuleContext loadModuleContext) {
        int result = hleKernelLoadHLEModule(loadModuleContext);
        if (result >= 0) {
            return result;
        }

        // Load module as ELF
        try {
        	loadModuleContext.moduleBuffer = null;
        	if (loadModuleContext.vFile != null) {
        		IVirtualFile vFile = loadModuleContext.vFile;
        		int size = (int) (vFile.length() - vFile.getPosition());
        		byte[] bytes = new byte[size];
        		result = vFile.ioRead(bytes, 0, size);
        		if (result >= 0) {
            		loadModuleContext.moduleBuffer = ByteBuffer.wrap(bytes, 0, result);
        		}
        	} else if (loadModuleContext.buffer != 0) {
        		byte[] bytes = new byte[loadModuleContext.bufferSize];
        		IMemoryReader memoryReader = MemoryReader.getMemoryReader(loadModuleContext.buffer, loadModuleContext.bufferSize, 1);
        		for (int i = 0; i < loadModuleContext.bufferSize; i++) {
        			bytes[i] = (byte) memoryReader.readNext();
        		}
        		loadModuleContext.moduleBuffer = ByteBuffer.wrap(bytes);
        	} else {
        		// TODO we need to properly handle the loading byUid (sceKernelLoadModuleByID)
        		// where the module to be loaded is only a part of a big file.
        		// We currently assume that the file contains only the module to be loaded.
                SeekableDataInput moduleInput = Modules.IoFileMgrForUserModule.getFile(loadModuleContext.fileName, loadModuleContext.flags);
                if (moduleInput != null) {
                    if (moduleInput instanceof UmdIsoFile) {
                        UmdIsoFile umdIsoFile = (UmdIsoFile) moduleInput;
                        String realFileName = umdIsoFile.getName();
                        if (realFileName != null && !loadModuleContext.fileName.endsWith(realFileName)) {
                        	loadModuleContext.fileName = realFileName;
                            result = hleKernelLoadHLEModule(loadModuleContext);
                            if (result >= 0) {
                                moduleInput.close();
                                return result;
                            }
                        }
                    }

                    byte[] moduleBytes = new byte[(int) moduleInput.length()];
                    moduleInput.readFully(moduleBytes);
                    moduleInput.close();
                    loadModuleContext.moduleBuffer = ByteBuffer.wrap(moduleBytes);
                }
        	}

        	if (loadModuleContext.moduleBuffer != null) {
                result = hleKernelLoadModuleFromModuleBuffer(loadModuleContext);
            } else {
                log.warn(String.format("hleKernelLoadModule(path='%s') can't find file", loadModuleContext.fileName));
                return ERROR_ERRNO_FILE_NOT_FOUND;
            }
        } catch (IOException e) {
            log.error(String.format("hleKernelLoadModule - Error while loading module %s", loadModuleContext.fileName), e);
            return -1;
        }

    	return result;
	}

    public int hleKernelStartModule(int uid, int argSize, TPointer argp, TPointer32 statusAddr, TPointer optionAddr, boolean waitForThreadEnd, IAction onModuleStartAction) {
        SceModule sceModule = Managers.modules.getModuleByUID(uid);
        SceKernelSMOption smOption = null;
        if (optionAddr.isNotNull()) {
            smOption = new SceKernelSMOption();
            smOption.read(optionAddr);
        }

        if (sceModule == null) {
            log.warn(String.format("sceKernelStartModule - unknown module UID 0x%X", uid));
            return ERROR_KERNEL_UNKNOWN_MODULE;
        }

        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceKernelStartModule starting module '%s'", sceModule.modname));
        }

        statusAddr.setValue(0);

        if (sceModule.isFlashModule) {
            // Trying to start a module loaded from flash0:
            // Do nothing...
            if (HLEModuleManager.getInstance().hasFlash0Module(sceModule.modname)) {
                log.info(String.format("IGNORING:sceKernelStartModule HLE module '%s'", sceModule.modname));
            } else {
                log.warn(String.format("IGNORING:sceKernelStartModule flash module '%s'", sceModule.modname));
            }
            sceModule.start();
            return sceModule.modid; // return the module id
        }

        ThreadManForUser threadMan = Modules.ThreadManForUserModule;
        int attribute = sceModule.attribute;
        int entryAddr = sceModule.entry_addr;
        if (Memory.isAddressGood(sceModule.module_start_func)) {
        	// Always take the module start function if one is defined.
            entryAddr = sceModule.module_start_func;
            if (sceModule.module_start_thread_attr != 0) {
            	attribute = sceModule.module_start_thread_attr;
            }
        }

        if (Memory.isAddressGood(entryAddr)) {
            int priority = 0x20;
            if (smOption != null && smOption.priority > 0) {
                priority = smOption.priority;
            } else if (sceModule.module_start_thread_priority > 0) {
                priority = sceModule.module_start_thread_priority;
            }

            int stackSize = 0x40000;
            if (smOption != null && smOption.stackSize > 0) {
                stackSize = smOption.stackSize;
            } else if (sceModule.module_start_thread_stacksize > 0) {
                stackSize = sceModule.module_start_thread_stacksize;
            }

            int mpidStack = sceModule.mpiddata;
            if (smOption != null && smOption.mpidStack > 0) {
            	mpidStack = smOption.mpidStack;
            }

            if (smOption != null && smOption.attribute != 0) {
                attribute = smOption.attribute;
            }

            // Remember the current thread as it can be changed by hleKernelStartThread.
            SceKernelThreadInfo currentThread = threadMan.getCurrentThread();

            SceKernelThreadInfo thread = threadMan.hleKernelCreateThread("SceModmgrStart", entryAddr, priority, stackSize, attribute, 0, mpidStack);
            // override inherited module id with the new module we are starting
            thread.moduleid = sceModule.modid;
            // Store the thread exit status into statusAddr when the thread terminates
            thread.exitStatusAddr = statusAddr;

            // Store any action that need to be executed when starting the module
            if (onModuleStartAction != null) {
            	thread.setOnThreadStartAction(onModuleStartAction);
            }

            sceModule.start();

            if (startModuleHandler != 0) {
            	// Install the start module handler so that it is called before the module entry point.
            	final int numberInstructions = 12;
            	final int newEntryAddr = thread.getStackAddr() + thread.stackSize - numberInstructions * 4;
            	int moduleAddr1 = sceModule.address >>> 16;
            	int moduleAddr2 = sceModule.address & 0xFFFF;
            	if (moduleAddr2 >= 0x8000) {
            		moduleAddr1 += 1;
            		moduleAddr2 = (moduleAddr2 - 0x10000) & 0xFFFF;
            	}
            	IMemoryWriter memoryWriter = MemoryWriter.getMemoryWriter(newEntryAddr, numberInstructions * 4, 4);
            	memoryWriter.writeNext(ADDIU(_sp, _sp, -16));
            	memoryWriter.writeNext(SW   (_a0, _sp, 0));
            	memoryWriter.writeNext(SW   (_a1, _sp, 4));
            	memoryWriter.writeNext(SW   (_ra, _sp, 8));
            	memoryWriter.writeNext(LUI  (_a0, moduleAddr1));
            	memoryWriter.writeNext(JAL  (startModuleHandler));
            	memoryWriter.writeNext(ADDIU(_a0, _a0, moduleAddr2));
            	memoryWriter.writeNext(LW   (_a0, _sp, 0));
            	memoryWriter.writeNext(LW   (_a1, _sp, 4));
            	memoryWriter.writeNext(LW   (_ra, _sp, 8));
            	memoryWriter.writeNext(J    (thread.entry_addr));
            	memoryWriter.writeNext(ADDIU(_sp, _sp, 16));
            	memoryWriter.flush();
            	thread.entry_addr = newEntryAddr;
            	thread.preserveStack = true; // Do not overwrite above code

            	RuntimeContext.invalidateRange(newEntryAddr, numberInstructions * 4);

            	if (log.isDebugEnabled()) {
            		log.debug(String.format("sceKernelStartModule installed hook to call startModuleHandler 0x%08X from 0x%08X for sceModule 0x%08X", startModuleHandler, newEntryAddr, sceModule.address));
            	}
            }

            // Start the module start thread
            threadMan.hleKernelStartThread(thread, argSize, argp, sceModule.gp_value);

            if (waitForThreadEnd) {
	            // Wait for the end of the module start thread.
	            // Do no return the thread exit status as the result of this call,
	            // return the module ID.
	            threadMan.hleKernelWaitThreadEnd(currentThread, thread.uid, TPointer32.NULL, false, false);
            }
        } else if (entryAddr == 0 || entryAddr == -1) {
            Modules.log.info("sceKernelStartModule - no entry address");
            sceModule.start();
        } else {
            log.warn(String.format("sceKernelStartModule - invalid entry address 0x%08X", entryAddr));
            return -1;
        }

        return sceModule.modid;
    }

    protected int getSelfModuleId() {
        return Modules.ThreadManForUserModule.getCurrentThread().moduleid;
    }

    protected SceModule getSelfModule() {
        return Managers.modules.getModuleByUID(getSelfModuleId());
    }

    public int hleKernelUnloadModule(int uid) {
        SceModule sceModule = Managers.modules.getModuleByUID(uid);
        if (sceModule == null) {
            log.warn(String.format("hleKernelUnloadModule unknown module UID 0x%X", uid));
            return -1;
        }
        if (sceModule.isModuleStarted() && !sceModule.isModuleStopped()) {
            log.warn(String.format("hleKernelUnloadModule module 0x%X is still running!", uid));
            return SceKernelErrors.ERROR_KERNEL_MODULE_CANNOT_REMOVE;
        }

        if (log.isDebugEnabled()) {
        	log.debug(String.format("hleKernelUnloadModule '%s'", sceModule.modname));
        }

        sceModule.unload();
        HLEModuleManager.getInstance().UnloadFlash0Module(sceModule);

        return sceModule.modid;
    }

    public boolean isSignChecked(String path) {
    	return path != null && path.startsWith("flash0:");
    }

	public int getStartModuleHandler() {
		return startModuleHandler;
	}

	public void setStartModuleHandler(int startModuleHandler) {
		this.startModuleHandler = startModuleHandler;
	}

    @HLEFunction(nid = 0xB7F46618, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelLoadModuleByID(int uid, @CanBeNull TPointer optionAddr) {
        String name = Modules.IoFileMgrForUserModule.getFileFilename(uid);

        if (log.isDebugEnabled()) {
            log.debug(String.format("sceKernelLoadModuleByID name='%s'", name));
        }

        SceKernelLMOption lmOption = null;
        if (optionAddr.isNotNull()) {
            lmOption = new SceKernelLMOption();
            lmOption.read(optionAddr);
            if (log.isInfoEnabled()) {
            	log.info(String.format("sceKernelLoadModuleByID options: %s", lmOption));
            }
        }

        LoadModuleContext loadModuleContext = new LoadModuleContext();
        loadModuleContext.fileName = name;
        loadModuleContext.uid = uid;
        loadModuleContext.lmOption = lmOption;
        loadModuleContext.byUid = true;
        loadModuleContext.needModuleInfo = true;
        loadModuleContext.allocMem = true;
        loadModuleContext.isSignChecked = isSignChecked(name);

        return hleKernelLoadModule(loadModuleContext);
    }

    @HLEFunction(nid = 0x977DE386, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    @HLEFunction(nid = 0x939E4270, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelLoadModule(PspString path, int flags, @CanBeNull TPointer optionAddr) {
        SceKernelLMOption lmOption = null;
        if (optionAddr.isNotNull()) {
            lmOption = new SceKernelLMOption();
            lmOption.read(optionAddr);
            if (log.isInfoEnabled()) {
            	log.info(String.format("sceKernelLoadModule options: %s", lmOption));
            }
        }

        LoadModuleContext loadModuleContext = new LoadModuleContext();
        loadModuleContext.fileName = path.getString();
        loadModuleContext.flags = flags;
        loadModuleContext.lmOption = lmOption;
        loadModuleContext.needModuleInfo = true;
        loadModuleContext.allocMem = true;
        loadModuleContext.isSignChecked = isSignChecked(path.getString());

        return hleKernelLoadModule(loadModuleContext);
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0x710F61B5, version = 150, checkInsideInterrupt = true)
    public int sceKernelLoadModuleMs() {
        return 0;
    }

    @HLEFunction(nid = 0xF9275D98, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelLoadModuleBufferUsbWlan(int bufSize, @BufferInfo(lengthInfo=LengthInfo.previousParameter, usage=Usage.in) TPointer buffer, int flags, @CanBeNull TPointer optionAddr) {
        SceKernelLMOption lmOption = null;
        if (optionAddr.isNotNull()) {
            lmOption = new SceKernelLMOption();
            lmOption.read(optionAddr);
            if (log.isInfoEnabled()) {
                log.info(String.format("sceKernelLoadModuleBufferUsbWlan options: %s", lmOption));
            }
        }

        LoadModuleContext loadModuleContext = new LoadModuleContext();
        loadModuleContext.fileName = buffer.toString();
        loadModuleContext.flags = flags;
        loadModuleContext.buffer = buffer.getAddress();
        loadModuleContext.bufferSize = bufSize;
        loadModuleContext.lmOption = lmOption;
        loadModuleContext.needModuleInfo = true;
        loadModuleContext.allocMem = true;
        loadModuleContext.isSignChecked = false;

        return Modules.ModuleMgrForUserModule.hleKernelLoadModule(loadModuleContext);
    }

    @HLEFunction(nid = 0x50F0C1EC, version = 150, checkInsideInterrupt = true)
    @HLEFunction(nid = 0x3FF74DF1, version = 150, checkInsideInterrupt = true)
    public int sceKernelStartModule(int uid, int argSize, @CanBeNull TPointer argp, @CanBeNull TPointer32 statusAddr, @CanBeNull TPointer optionAddr) {
    	return hleKernelStartModule(uid, argSize, argp, statusAddr, optionAddr, true, null);
    }

    @HLEFunction(nid = 0xD1FF982A, version = 150, checkInsideInterrupt = true)
    @HLEFunction(nid = 0xE5D6087B, version = 150, checkInsideInterrupt = true)
    public int sceKernelStopModule(int uid, int argSize, @CanBeNull TPointer argp, @CanBeNull TPointer32 statusAddr, @CanBeNull TPointer optionAddr) {
        SceModule sceModule = Managers.modules.getModuleByUID(uid);
        SceKernelSMOption smOption = null;
        if (optionAddr.isNotNull()) {
            smOption = new SceKernelSMOption();
            smOption.read(optionAddr);
        }

        if (sceModule == null) {
            log.warn("sceKernelStopModule - unknown module UID 0x" + Integer.toHexString(uid));
            return ERROR_KERNEL_UNKNOWN_MODULE;
        }

        statusAddr.setValue(0);

        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceKernelStopModule '%s'", sceModule.modname));
        }

        if (sceModule.isFlashModule) {
            // Trying to stop a module loaded from flash0:
            // Shouldn't get here...
            if (HLEModuleManager.getInstance().hasFlash0Module(sceModule.modname)) {
                log.info("IGNORING:sceKernelStopModule HLE module '" + sceModule.modname + "'");
            } else {
                log.warn("IGNORING:sceKernelStopModule flash module '" + sceModule.modname + "'");
            }
            sceModule.stop();
            return 0; // Fake success.
        }

        if (Memory.isAddressGood(sceModule.module_stop_func)) {
            int priority = 0x20;
            if (smOption != null && smOption.priority > 0) {
                priority = smOption.priority;
            } else if (sceModule.module_stop_thread_priority > 0) {
                priority = sceModule.module_stop_thread_priority;
            }

            int stackSize = 0x40000;
            if (smOption != null && smOption.stackSize > 0) {
                stackSize = smOption.stackSize;
            } else if (sceModule.module_stop_thread_stacksize > 0) {
                stackSize = sceModule.module_stop_thread_stacksize;
            }

            int mpidStack = sceModule.mpiddata;
            if (smOption != null && smOption.mpidStack > 0) {
            	mpidStack = smOption.mpidStack;
            }

            int attribute = sceModule.module_stop_thread_attr;
            if (smOption != null) {
                attribute = smOption.attribute;
            }

            ThreadManForUser threadMan = Modules.ThreadManForUserModule;
            SceKernelThreadInfo currentThread = threadMan.getCurrentThread();

            SceKernelThreadInfo thread = threadMan.hleKernelCreateThread("SceModmgrStop",
                    sceModule.module_stop_func, priority,
                    stackSize, attribute, 0, mpidStack);

            thread.moduleid = sceModule.modid;
            // Store the thread exit status into statusAddr when the thread terminates
            thread.exitStatusAddr = statusAddr;
            sceModule.stop();

            // Start the "stop" thread...
            threadMan.hleKernelStartThread(thread, argSize, argp, sceModule.gp_value);

            // ...and wait for its end.
            threadMan.hleKernelWaitThreadEnd(currentThread, thread.uid, TPointer32.NULL, false, false);
        } else if (sceModule.module_stop_func == 0) {
            log.info("sceKernelStopModule - module has no stop function");
            sceModule.stop();
        } else if (sceModule.isModuleStopped()) {
            log.warn("sceKernelStopModule - module already stopped");
            return SceKernelErrors.ERROR_KERNEL_MODULE_ALREADY_STOPPED;
        } else {
            log.warn(String.format("sceKernelStopModule - invalid stop function 0x%08X", sceModule.module_stop_func));
            return -1;
        }

        return 0;
    }

    @HLEFunction(nid = 0x2E0911AA, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    @HLEFunction(nid = 0x387E3CA9, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelUnloadModule(int uid) {
    	return hleKernelUnloadModule(uid);
    }

    @HLEFunction(nid = 0xD675EBB8, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelSelfStopUnloadModule(int exitCode, int argSize, @CanBeNull TPointer argp) {
        SceModule sceModule = getSelfModule();
        ThreadManForUser threadMan = Modules.ThreadManForUserModule;
        SceKernelThreadInfo thread = null;
        if (Memory.isAddressGood(sceModule.module_stop_func)) {
            // Start the module stop thread function.
            thread = threadMan.hleKernelCreateThread("SceModmgrStop",
                    sceModule.module_stop_func, sceModule.module_stop_thread_priority,
                    sceModule.module_stop_thread_stacksize, sceModule.module_stop_thread_attr, 0, sceModule.mpiddata);
            thread.moduleid = sceModule.modid;
            // Unload the module when the stop thread will be deleted
            thread.unloadModuleAtDeletion = true;
        } else {
        	// Stop and unload the module immediately
            sceModule.stop();
        	sceModule.unload();
        }

        threadMan.hleKernelExitDeleteThread();  // Delete the current thread.
        if (thread != null) {
        	threadMan.hleKernelStartThread(thread, argSize, argp, sceModule.gp_value);
        }

        return 0;
    }

    @HLEFunction(nid = 0x8F2DF740, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelStopUnloadSelfModuleWithStatus(int exitCode, int argSize, @CanBeNull TPointer argp, @CanBeNull TPointer32 statusAddr, @CanBeNull TPointer optionAddr) {
        SceModule sceModule = getSelfModule();

        if (log.isInfoEnabled()) {
        	log.info(String.format("sceKernelStopUnloadSelfModuleWithStatus %s, exitCode=0x%X", sceModule, exitCode));
        }

        ThreadManForUser threadMan = Modules.ThreadManForUserModule;
        SceKernelThreadInfo thread = null;
        statusAddr.setValue(0);
        if (Memory.isAddressGood(sceModule.module_stop_func)) {
            // Start the module stop thread function.
            statusAddr.setValue(0); // TODO set to return value of the thread (when it exits, of course)

            thread = threadMan.hleKernelCreateThread("SceModmgrStop",
                    sceModule.module_stop_func, sceModule.module_stop_thread_priority,
                    sceModule.module_stop_thread_stacksize, sceModule.module_stop_thread_attr, optionAddr.getAddress(), sceModule.mpiddata);
            thread.moduleid = sceModule.modid;
            // Store the thread exit status into statusAddr when the thread terminates
            thread.exitStatusAddr = statusAddr;
            threadMan.getCurrentThread().exitStatus = exitCode; // Set the current thread's exit status.
            // Unload the module when the stop thread will be deleted
            thread.unloadModuleAtDeletion = true;
        } else {
        	// Stop and unload the module immediately
            sceModule.stop();
        	sceModule.unload();
        }

        threadMan.hleKernelExitDeleteThread();  // Delete the current thread.
        if (thread != null) {
        	threadMan.hleKernelStartThread(thread, argSize, argp, sceModule.gp_value);
        }

        return 0;
    }

    @HLEFunction(nid = 0xCC1D3699, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelStopUnloadSelfModule(int argSize, @CanBeNull TPointer argp, @CanBeNull TPointer32 statusAddr, @CanBeNull TPointer optionAddr) {
        SceModule sceModule = getSelfModule();

        if (log.isInfoEnabled()) {
        	log.info(String.format("sceKernelStopUnloadSelfModule %s", sceModule));
        }

        ThreadManForUser threadMan = Modules.ThreadManForUserModule;
        SceKernelThreadInfo thread = null;
        statusAddr.setValue(0);
        if (Memory.isAddressGood(sceModule.module_stop_func)) {
            // Start the module stop thread function.
            thread = threadMan.hleKernelCreateThread("SceModmgrStop",
                    sceModule.module_stop_func, sceModule.module_stop_thread_priority,
                    sceModule.module_stop_thread_stacksize, sceModule.module_stop_thread_attr, optionAddr.getAddress(), sceModule.mpiddata);
            thread.moduleid = sceModule.modid;
            // Store the thread exit status into statusAddr when the thread terminates
            thread.exitStatusAddr = statusAddr;
            // Unload the module when the stop thread will be deleted
            thread.unloadModuleAtDeletion = true;
        } else {
        	// Stop and unload the module immediately
        	sceModule.stop();
        	sceModule.unload();
        }

        threadMan.hleKernelExitDeleteThread();  // Delete the current thread.
        if (thread != null) {
        	threadMan.hleKernelStartThread(thread, argSize, argp, sceModule.gp_value);
        }

        return 0;
    }

    /**
     * Get a list of module IDs.
     * @param resultBuffer      Buffer to store the module list
     * @param resultBufferSize  Number of bytes in the resultBuffer
     * @param idCountAddr       Returns the number of module ids
     * @return >= 0 on success 
     */
    @HLEFunction(nid = 0x644395E2, version = 150, checkInsideInterrupt = true)
    public int sceKernelGetModuleIdList(TPointer32 resultBuffer, int resultBufferSize, TPointer32 idCountAddr) {
    	int idCount = 0;
    	int resultBufferOffset = 0;
    	for (SceModule module : Managers.modules.values()) {
    		if (!module.isFlashModule && module.isLoaded) {
    			if (resultBufferOffset < resultBufferSize) {
    				resultBuffer.setValue(resultBufferOffset, module.modid);
    				resultBufferOffset += 4;
    			}
    			idCount++;
    		}
    	}
    	idCountAddr.setValue(idCount);

    	return 0;
    }

    @HLEFunction(nid = 0x748CBED9, version = 150, checkInsideInterrupt = true)
    @HLEFunction(nid = 0x22BDBEFF, version = 150, checkInsideInterrupt = true)
    public int sceKernelQueryModuleInfo(int uid, TPointer infoAddr) {
        SceModule sceModule = Managers.modules.getModuleByUID(uid);
        if (sceModule == null) {
            log.warn(String.format("sceKernelQueryModuleInfo unknown module UID 0x%X", uid));
            return -1;
        }

        SceKernelModuleInfo moduleInfo = new SceKernelModuleInfo();
        moduleInfo.copy(sceModule);
        moduleInfo.write(infoAddr);

        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceKernelQueryModuleInfo returning %s", Utilities.getMemoryDump(infoAddr.getAddress(), Math.min(SceKernelModuleInfo.SIZE_OF, infoAddr.getValue32()))));
        }

        return 0;
    }

    @HLEFunction(nid = 0xF0A26395, version = 150, checkInsideInterrupt = true)
    public int sceKernelGetModuleId() {
        int moduleId = getSelfModuleId();

        if (log.isDebugEnabled()) {
            log.debug(String.format("sceKernelGetModuleId returning 0x%X", moduleId));
        }

        return moduleId;
    }

    @HLEFunction(nid = 0xD8B73127, version = 150, checkInsideInterrupt = true)
    public int sceKernelGetModuleIdByAddress(TPointer addr) {
        SceModule module = Managers.modules.getModuleByAddress(addr.getAddress());
        if (module == null) {
            log.warn(String.format("sceKernelGetModuleIdByAddress addr=%s module not found", addr));
            return -1;
        }

        return module.modid;
    }

    @HLEFunction(nid = 0xA1A78C58, version = 150, canModifyCode = true)
    @HLEFunction(nid = 0xCE0A74A5, version = 660, canModifyCode = true)
    public int sceKernelLoadModuleDisc(PspString path, int flags, @CanBeNull TPointer optionAddr) {
        SceKernelLMOption lmOption = null;
        if (optionAddr.isNotNull()) {
            lmOption = new SceKernelLMOption();
            lmOption.read(optionAddr);
            if (log.isInfoEnabled()) {
            	log.info(String.format("sceKernelLoadModuleDisc options: %s", lmOption));
            }
        }

        LoadModuleContext loadModuleContext = new LoadModuleContext();
        loadModuleContext.fileName = path.getString();
        loadModuleContext.flags = flags;
        loadModuleContext.lmOption = lmOption;
        loadModuleContext.allocMem = true;

        return hleKernelLoadModule(loadModuleContext);
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0xFEF27DC1, version = 271, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelLoadModuleDNAS(PspString path, @BufferInfo(lengthInfo = LengthInfo.fixedLength, length = 16, usage = Usage.in) TPointer key, int flags, @CanBeNull @BufferInfo(lengthInfo = LengthInfo.variableLength, usage = Usage.in) TPointer optionAddr) {
        SceKernelLMOption lmOption = null;
        if (optionAddr.isNotNull()) {
            lmOption = new SceKernelLMOption();
            lmOption.read(optionAddr);
            if (log.isInfoEnabled()) {
                log.info(String.format("sceKernelLoadModuleDNAS options: %s", lmOption));
            }
        }

        StringBuilder localFileName = new StringBuilder();
        IVirtualFileSystem vfs = Modules.IoFileMgrForUserModule.getVirtualFileSystem(path.getString(), localFileName);
        if (vfs == null) {
        	return ERROR_ERRNO_DEVICE_NOT_FOUND;
        }

        IVirtualFile vFile = vfs.ioOpen(localFileName.toString(), IoFileMgrForUser.PSP_O_RDONLY, 0);
    	if (vFile == null) {
    		return ERROR_ERRNO_FILE_NOT_FOUND;
    	}
    	PGDVirtualFile pgdFile = new PGDVirtualFile(key.getArray8(16), vFile);

        LoadModuleContext loadModuleContext = new LoadModuleContext();
        loadModuleContext.fileName = path.getString();
        loadModuleContext.vFile = pgdFile;
        loadModuleContext.flags = flags;
        loadModuleContext.lmOption = lmOption;
        loadModuleContext.needModuleInfo = true;
        loadModuleContext.allocMem = true;

        return hleKernelLoadModule(loadModuleContext);
    }

    @HLEFunction(nid = 0xF2D8D1B4, version = 271, canModifyCode = true)
    public int sceKernelLoadModuleNpDrm(PspString path, int flags, @CanBeNull TPointer optionAddr) {
        SceKernelLMOption lmOption = null;
        if (optionAddr.isNotNull()) {
            lmOption = new SceKernelLMOption();
            lmOption.read(optionAddr);
            if (log.isInfoEnabled()) {
                log.info(String.format("sceKernelLoadModuleNpDrm options: %s", lmOption));
            }
        }

        // SPRX modules can't be decrypted yet.
        if (!Modules.scePspNpDrm_userModule.getDisableDLCStatus()) {
            log.warn(String.format("sceKernelLoadModuleNpDrm detected encrypted DLC module: %s", path.getString()));
            return SceKernelErrors.ERROR_NPDRM_INVALID_PERM;
        }

        LoadModuleContext loadModuleContext = new LoadModuleContext();
        loadModuleContext.fileName = path.getString();
        loadModuleContext.flags = flags;
        loadModuleContext.lmOption = lmOption;
        loadModuleContext.needModuleInfo = true;
        loadModuleContext.allocMem = true;

        return hleKernelLoadModule(loadModuleContext);
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0xE4C4211C, version = 150, checkInsideInterrupt = true, canModifyCode = true)
    public int sceKernelLoadModuleWithBlockOffset(PspString path, int memoryBlockId, int memoryBlockOffset, int flags) {
    	return 0;
    }

    @HLEFunction(nid = 0xD2FBC957, version = 150)
    public int sceKernelGetModuleGPByAddress(int address, TPointer32 gpAddr) {
        SceModule module = Managers.modules.getModuleByAddress(address);
        if (module == null) {
            log.warn(String.format("sceKernelGetModuleGPByAddress not found module address=0x%08X", address));
            return -1;
        }

        gpAddr.setValue(module.gp_value);

        return 0;
    }
}