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

import jpcsp.Emulator;
import jpcsp.Memory;
import jpcsp.MemoryMap;
import jpcsp.NIDMapper;
import jpcsp.Allegrex.Decoder;
import jpcsp.Allegrex.Instructions;
import jpcsp.Allegrex.Common.Instruction;
import jpcsp.Allegrex.compiler.RuntimeContext;
import jpcsp.Allegrex.compiler.RuntimeContextLLE;
import jpcsp.HLE.BufferInfo;
import jpcsp.HLE.BufferInfo.LengthInfo;
import jpcsp.HLE.BufferInfo.Usage;
import jpcsp.HLE.HLEFunction;
import jpcsp.HLE.HLELogging;
import jpcsp.HLE.HLEModule;
import jpcsp.HLE.HLEModuleFunction;
import jpcsp.HLE.HLEModuleManager;
import jpcsp.HLE.HLEUnimplemented;
import jpcsp.HLE.PspString;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.TPointer32;
import jpcsp.HLE.Modules;
import jpcsp.HLE.kernel.Managers;
import jpcsp.HLE.kernel.types.IAction;
import jpcsp.HLE.kernel.types.SceKernelThreadInfo;
import jpcsp.HLE.kernel.types.SceLoadCoreBootInfo;
import jpcsp.HLE.kernel.types.SceLoadCoreBootModuleInfo;
import jpcsp.HLE.kernel.types.SceLoadCoreExecFileInfo;
import jpcsp.HLE.kernel.types.SceModule;
import jpcsp.HLE.kernel.types.SceResidentLibraryEntryTable;
import jpcsp.HLE.kernel.types.SysMemThreadConfig;
import jpcsp.HLE.modules.SysMemUserForUser.SysMemInfo;
import jpcsp.format.Elf32Header;
import jpcsp.format.Elf32ProgramHeader;
import jpcsp.format.Elf32SectionHeader;
import jpcsp.format.PSPModuleInfo;
import jpcsp.util.HLEUtilities;
import jpcsp.util.Utilities;

import static jpcsp.Allegrex.Common._a0;
import static jpcsp.Allegrex.Common._a1;
import static jpcsp.Allegrex.Common._v0;
import static jpcsp.Allegrex.Common._zr;
import static jpcsp.Allegrex.compiler.RuntimeContextLLE.getFirmwareVersion;
import static jpcsp.AllegrexOpcodes.ADDIU;
import static jpcsp.AllegrexOpcodes.ADDU;
import static jpcsp.AllegrexOpcodes.J;
import static jpcsp.AllegrexOpcodes.JR;
import static jpcsp.AllegrexOpcodes.LUI;
import static jpcsp.AllegrexOpcodes.LW;
import static jpcsp.AllegrexOpcodes.SPECIAL;
import static jpcsp.HLE.HLEModuleManager.HLESyscallNid;
import static jpcsp.HLE.modules.ModuleMgrForUser.SCE_HEADER_LENGTH;
import static jpcsp.HLE.modules.SysMemUserForUser.KERNEL_PARTITION_ID;
import static jpcsp.HLE.modules.SysMemUserForUser.PSP_SMEM_Low;
import static jpcsp.util.HLEUtilities.ADDIU;
import static jpcsp.util.HLEUtilities.JR;
import static jpcsp.util.HLEUtilities.NOP;
import static jpcsp.util.HLEUtilities.SW;
import static jpcsp.util.HLEUtilities.SYSCALL;
import static jpcsp.Loader.SCE_MAGIC;
import static jpcsp.format.Elf32SectionHeader.SHF_ALLOCATE;
import static jpcsp.format.Elf32SectionHeader.SHF_EXECUTE;
import static jpcsp.format.Elf32SectionHeader.SHT_PROGBITS;
import static jpcsp.format.PSP.PSP_HEADER_SIZE;
import static jpcsp.format.PSP.PSP_MAGIC;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

public class LoadCoreForKernel extends HLEModule {
    public static Logger log = Modules.getLogger("LoadCoreForKernel");
    private Set<Integer> dummyModuleData;
    private TPointer syscallStubAddr;
    private int availableSyscallStubs;
    private final Map<String, String> functionNames = new HashMap<String, String>();
    private final Map<Integer, Integer> functionNids = new HashMap<Integer, Integer>();
    private int loadCoreBaseAddress;
    private int threadManInfo;
    public int threadManInfoCurrentThreadOffset = 0;
    public int threadManInfoNextThreadOffset = 4;
    public int threadManInfoThreadTypeOffset;
    public int threadManInfoSleepingThreadsOffset;
    public int threadManInfoDelayedThreadsOffset;
    public int threadManInfoStoppedThreadsOffset;
    public int threadManInfoSuspendedThreadsOffset;
    public int threadManInfoDeadThreadsOffset;
    public int threadManInfoUnknownThreadsOffset;
    public int threadManInfoReadyThreadsOffset;
    private int syscallBaseAddress;

	private class OnModuleStartAction implements IAction {
		@Override
		public void execute() {
			onModuleStart();
		}
	}

	private class AfterSceKernelFindModuleByName implements IAction {
		private SceKernelThreadInfo thread;
		private TPointer moduleNameAddr;

		public AfterSceKernelFindModuleByName(SceKernelThreadInfo thread, TPointer moduleNameAddr) {
			this.thread = thread;
			this.moduleNameAddr = moduleNameAddr;
		}

		@Override
		public void execute() {
			fixExistingModule(moduleNameAddr, thread.cpuContext._v0);
		}
	}

	public IAction getModuleStartAction() {
    	return new OnModuleStartAction();
    }

	/**
	 * Hook to force the creation of a larger heap used by sceKernelRegisterLibrary().
	 * 
	 * @param partitionId the partitionId of the heap
	 * @param size        the size of the heap
	 * @param flags       the flags for the heap creation
	 * @param name        the heap name
	 * @return            the new size of the heap
	 */
	public int hleKernelCreateHeapHook(int partitionId, int size, int flags, String name) {
		if ("SceKernelLoadCore".equals(name) && partitionId == KERNEL_PARTITION_ID && size == 0x1000 && flags == 0x1) {
			size += 0x4000;
		}

		return size;
	}

    public boolean decodeInitModuleData(TPointer buffer, int size, TPointer32 resultSize) {
    	if (dummyModuleData == null || !dummyModuleData.contains(buffer.getAddress())) {
    		return false;
    	}

    	buffer.memmove(buffer.getAddress() + PSP_HEADER_SIZE, size - PSP_HEADER_SIZE);
    	resultSize.setValue(size - PSP_HEADER_SIZE);

    	return true;
    }

	private TPointer allocMem(int size) {
		SysMemInfo memInfo = Modules.SysMemUserForUserModule.malloc(KERNEL_PARTITION_ID, "LoadCore-StartModuleParameters", PSP_SMEM_Low, size, 0);
		if (memInfo == null) {
			log.error(String.format("Cannot allocate memory for loadcore.prx start parameters"));
			return TPointer.NULL;
		}

		TPointer pointer = new TPointer(Memory.getInstance(), memInfo.addr);
		pointer.clear(size);

		return pointer;
    }

	private void freeMem(TPointer pointer) {
		SysMemInfo memInfo = Modules.SysMemUserForUserModule.getSysMemInfoByAddress(pointer.getAddress());
		if (memInfo != null) {
			Modules.SysMemUserForUserModule.free(memInfo);
		}
	}

	private int addSyscallStub(int syscallCode) {
		// Each stub requires 8 bytes
    	final int stubSize = 8;

    	if (availableSyscallStubs <= 0) {
    		availableSyscallStubs = 128;
    		syscallStubAddr = allocMem(availableSyscallStubs * stubSize);
    		if (syscallStubAddr.isNull()) {
    			availableSyscallStubs = 0;
    			log.error(String.format("No more free memory to create a new Syscall stub!"));
    			return 0;
    		}
    	}

    	int stubAddr = syscallStubAddr.getAddress();
    	syscallStubAddr.setValue32(0, JR());
    	syscallStubAddr.setValue32(4, SYSCALL(syscallCode));

    	if (log.isDebugEnabled()) {
    		log.debug(String.format("Adding a syscall 0x%X stub at 0x%08X", syscallCode, stubAddr));
    	}

    	syscallStubAddr.add(stubSize);
    	availableSyscallStubs--;

    	return stubAddr;
    }

    private TPointer createDummyModule(SceLoadCoreBootModuleInfo sceLoadCoreBootModuleInfo, String moduleName, int initCodeOffset) {
		final int moduleInfoSizeof = new PSPModuleInfo().sizeof();
		// sceInit module code
		final int initCodeSize = 8;
		int totalSize = SCE_HEADER_LENGTH + PSP_HEADER_SIZE + Elf32Header.sizeof() + Elf32ProgramHeader.sizeof() + Elf32SectionHeader.sizeof() + moduleInfoSizeof + initCodeOffset + initCodeSize;
		TPointer modBuf = allocMem(totalSize);
		if (log.isTraceEnabled()) {
			log.trace(String.format("Allocated dummy module buffer %s", modBuf));
		}
		sceLoadCoreBootModuleInfo.modBuf = modBuf;

		int offset = 0;
		// SCE header
		modBuf.setValue32(offset + 0, SCE_MAGIC);
		modBuf.setValue32(offset + 4, SCE_HEADER_LENGTH); // SceHeader.size
		offset += SCE_HEADER_LENGTH;

		// PSP header
		dummyModuleData.add(modBuf.getAddress() + offset);
		modBuf.setValue32(offset + 0, PSP_MAGIC);
		modBuf.setValue16(offset + 4, (short) 0x1000); // PspHeader.mod_attr = SCE_MODULE_KERNEL
		modBuf.setValue32(offset + 44, totalSize - SCE_HEADER_LENGTH); // PspHeader.psp_size, must be > 0
		modBuf.setValue32(offset + 48, 0); // PspHeader.boot_entry = 0
		modBuf.setValue32(offset + 52, 0x80000000 + Elf32Header.sizeof() + Elf32ProgramHeader.sizeof() + Elf32SectionHeader.sizeof()); // PspHeader.modinfo_offset = IS_KERNEL_ADDR
		modBuf.setValue8(offset + 124, (byte) 2); // PspHeader.dec_mode = DECRYPT_MODE_KERNEL_MODULE
		modBuf.setValue32(offset + 208, 0); // PspHeader.tag = 0
		offset += PSP_HEADER_SIZE;

		// ELF header
		modBuf.setValue32(offset + 0, Elf32Header.ELF_MAGIC);
		modBuf.setValue8(offset + 4, (byte) 1); // Elf32Header.e_class = ELFCLASS32
		modBuf.setValue8(offset + 5, (byte) 1); // Elf32Header.e_data = ELFDATA2LSB
		modBuf.setValue16(offset + 16, (short) Elf32Header.ET_SCE_PRX); // Elf32Header.e_type = ET_SCE_PRX
		modBuf.setValue16(offset + 18, (short) Elf32Header.E_MACHINE_MIPS); // Elf32Header.e_machine = EM_MIPS_ALLEGREX
		modBuf.setValue32(offset + 24, moduleInfoSizeof + initCodeOffset); // Elf32Header.e_entry = dummy entry point, must be != 0
		modBuf.setValue32(offset + 28, Elf32Header.sizeof()); // Elf32Header.e_phoff = sizeof(Elf32Header)
		modBuf.setValue32(offset + 32, Elf32Header.sizeof() + Elf32ProgramHeader.sizeof()); // Elf32Header.e_shoff = sizeof(Elf32Header) + sizeof(Elf32ProgramHeader)
		modBuf.setValue16(offset + 44, (short) 1); // Elf32Header.e_phnum, must be > 0
		modBuf.setValue16(offset + 48, (short) 1); // Elf32Header.e_shnum, must be > 0
		modBuf.setValue16(offset + 50, (short) 0); // Elf32Header.e_shstrndx = 0
		offset += Elf32Header.sizeof();

		// ELF Program header
		modBuf.setValue32(offset + 0, 1); // Elf32ProgramHeader.p_type = PT_LOAD
		modBuf.setValue32(offset + 4, Elf32Header.sizeof() + Elf32ProgramHeader.sizeof() + Elf32SectionHeader.sizeof()); // Elf32ProgramHeader.p_offset
		modBuf.setValue32(offset + 8, 0); // Elf32ProgramHeader.p_vaddr = 0
		modBuf.setValue32(offset + 12, Elf32Header.sizeof() + Elf32ProgramHeader.sizeof() + Elf32SectionHeader.sizeof()); // Elf32ProgramHeader.p_paddr = sizeof(Efl32Header) + sizeof(Elf32ProgramHeader)
		modBuf.setValue32(offset + 16, moduleInfoSizeof + initCodeOffset + initCodeSize); // Elf32ProgramHeader.p_filesz
		modBuf.setValue32(offset + 20, moduleInfoSizeof + initCodeOffset + initCodeSize); // Elf32ProgramHeader.p_memsz
		offset += Elf32ProgramHeader.sizeof();

		// ELF Section header
		modBuf.setValue32(offset + 4, SHT_PROGBITS); // Elf32SectionHeader.sh_type = SHT_PROGBITS
		modBuf.setValue32(offset + 8, SHF_ALLOCATE | SHF_EXECUTE); // Elf32SectionHeader.sh_flags = SHF_ALLOCATE | SHF_EXECUTE
		modBuf.setValue32(offset + 16, 1); // Elf32SectionHeader.sh_offset, must be > 0
		modBuf.setValue32(offset + 20, 0); // Elf32SectionHeader.sh_size = 0
		offset += Elf32SectionHeader.sizeof();

		// PSP Module Info
		modBuf.setStringNZ(offset + 4, 28, moduleName); // PSPModuleInfo.m_name = moduleName
		offset += new PSPModuleInfo().sizeof();

		// Allow the entry point of the module to be at different addresses
		offset += initCodeOffset;

		// Module entry point:
		// Code for "return SCE_KERNEL_NO_RESIDENT"
		TPointer entryPointCode = new TPointer(modBuf, offset);
		modBuf.setValue32(offset + 0, JR());
		modBuf.setValue32(offset + 4, ADDIU(_v0, _zr, 0));
		offset += initCodeSize;

		if (log.isDebugEnabled()) {
			log.debug(String.format("createDummyModule moduleName='%s', entryPointCode=%s", moduleName, entryPointCode));
		}
		return entryPointCode;
    }

    private void createDummyInitModule(SceLoadCoreBootModuleInfo sceLoadCoreBootModuleInfo) {
		TPointer entryPointCode = createDummyModule(sceLoadCoreBootModuleInfo, "sceInit", 4);

		// After loading the sceInit module, fix the previously loaded module(s)
		entryPointCode.setValue32(4, SYSCALL(this, "hleLoadCoreInitStart"));
    }

    private void addKernelUserLibs(SysMemThreadConfig sysMemThreadConfig, String libName, int[] nids) {
    	SceResidentLibraryEntryTable sceResidentLibraryEntryTable = new SceResidentLibraryEntryTable();
    	sceResidentLibraryEntryTable.libNameAddr = allocMem(libName.length() + 1);
    	sceResidentLibraryEntryTable.libNameAddr.setStringZ(libName);
    	sceResidentLibraryEntryTable.version[0] = (byte) 0x11;
    	// Do not specify the SCE_LIB_SYSCALL_EXPORT attribute as we will link only through jumps.
    	sceResidentLibraryEntryTable.attribute = 0x0001;
    	sceResidentLibraryEntryTable.len = 5;
    	sceResidentLibraryEntryTable.vStubCount = 0;
    	sceResidentLibraryEntryTable.stubCount = 0;
    	sceResidentLibraryEntryTable.vStubCountNew = 0;

    	int[] addresses = new int[nids.length];
    	boolean[] areVariableExports = new boolean[nids.length];

    	TPointer sceResidentLibraryEntryTableAddr = allocMem(sceResidentLibraryEntryTable.sizeof() + nids.length * 8);
    	TPointer entryTable = new TPointer(sceResidentLibraryEntryTableAddr, sceResidentLibraryEntryTable.sizeof());
    	sceResidentLibraryEntryTable.entryTable = entryTable;

    	NIDMapper nidMapper = NIDMapper.getInstance();
    	// Analyze all the NIDs and separate into function or variable stubs
    	for (int i = 0; i < nids.length; i++) {
    		int nid = nids[i];
    		int address = nidMapper.getAddressByNid(nid, libName);
    		// No address for the NID?
    		// I.e. is the NID only called through a syscall?
			if (address == 0) {
				int syscallCode = nidMapper.getSyscallByNid(nid);
				if (syscallCode >= 0) {
					// Add a syscall stub as we always link through a jump.
					address = addSyscallStub(syscallCode);
				}
			}
			boolean isVariableExport = nidMapper.isVariableExportByAddress(address);

			if (log.isDebugEnabled()) {
				log.debug(String.format("Registering library '%s' NID 0x%08X at address 0x%08X (variableExport=%b)", libName, nid, address, isVariableExport));
			}

			addresses[i] = address;
			areVariableExports[i] = isVariableExport;

			if (isVariableExport) {
				sceResidentLibraryEntryTable.vStubCountNew++;
			} else {
				sceResidentLibraryEntryTable.stubCount++;
			}
    	}
    	sceResidentLibraryEntryTable.write(sceResidentLibraryEntryTableAddr);

    	// Write the function and variable table entries in the following order:
    	// - stubCount NID values
    	// - vStubCountNew NID values
    	// - stubCount addresses
    	// - vStubCountNew addresses
    	int functionIndex = 0;
    	int variableIndex = sceResidentLibraryEntryTable.stubCount;
    	for (int i = 0; i < nids.length; i++) {
    		int index = areVariableExports[i] ? variableIndex++ : functionIndex++;
			entryTable.setValue32(index * 4, nids[i]);
			entryTable.setValue32((index + nids.length) * 4, addresses[i]);
    	}

    	int userLibIndex = sysMemThreadConfig.numExportLibs - sysMemThreadConfig.numKernelLibs;
    	if (userLibIndex == sysMemThreadConfig.userLibs.length) {
    		sysMemThreadConfig.userLibs = Utilities.extendArray(sysMemThreadConfig.userLibs, 1);
    	}
    	sysMemThreadConfig.userLibs[userLibIndex] = sceResidentLibraryEntryTableAddr;
    	sysMemThreadConfig.numExportLibs++;
    }

    private void addKernelUserLibs(SysMemThreadConfig sysMemThreadConfig, String libName) {
    	// LoadCoreForKernel is already registered by the initialization code itself
    	if ("LoadCoreForKernel".equals(libName)) {
    		return;
    	}

    	int[] nids = NIDMapper.getInstance().getModuleNids(libName);
    	if (nids == null) {
    		log.warn(String.format("Unknown library '%s', no NIDs found", libName));
    		return;
    	}

    	// Keep the list of NIDs sorted for easier debugging
    	Arrays.sort(nids);

    	addKernelUserLibs(sysMemThreadConfig, libName, nids);
    }

    private void prepareKernelLibs(SysMemThreadConfig sysMemThreadConfig) {
    	// Pass all the available libraries/modules NIDs to the
    	// initialization code of loadcore.prx so that they will be
    	// registered by calling sceKernelRegisterLibrary().
    	String[] moduleNames = NIDMapper.getInstance().getModuleNames();
    	for (String moduleName : moduleNames) {
    		addKernelUserLibs(sysMemThreadConfig, moduleName);
    	}
    }

    private void addExistingModule(SceLoadCoreBootInfo sceLoadCoreBootInfo, String moduleName) {
    	SceModule module = Managers.modules.getModuleByName(moduleName);
    	if (module == null) {
    		if (log.isDebugEnabled()) {
    			log.debug(String.format("addExistingModule could not find module '%s'", moduleName));
    		}
    		return;
    	}

    	SceLoadCoreBootModuleInfo sceLoadCoreBootModuleInfo = new SceLoadCoreBootModuleInfo();
    	createDummyModule(sceLoadCoreBootModuleInfo, moduleName, 0);

		TPointer sceLoadCoreBootModuleInfoAddr = new TPointer(sceLoadCoreBootInfo.startAddr, sceLoadCoreBootInfo.numModules * sceLoadCoreBootModuleInfo.sizeof());
		sceLoadCoreBootModuleInfo.write(sceLoadCoreBootModuleInfoAddr);
		sceLoadCoreBootInfo.numModules++;
    }

    @HLEFunction(nid = HLESyscallNid, version = 150)
    public int hleLoadCoreInitStart(int argc, @BufferInfo(lengthInfo=LengthInfo.fixedLength, length=128, usage=Usage.in) TPointer sceLoadCoreBootInfoAddr) {
    	SceLoadCoreBootInfo sceLoadCoreBootInfo = new SceLoadCoreBootInfo();
    	sceLoadCoreBootInfo.read(sceLoadCoreBootInfoAddr);

    	int sceKernelFindModuleByName = NIDMapper.getInstance().getAddressByName("sceKernelFindModuleByName");
    	if (sceKernelFindModuleByName != 0) {
			SceKernelThreadInfo thread = Modules.ThreadManForUserModule.getCurrentThread();

			// Fix the previously loaded scePaf_Module
			String moduleName = "scePaf_Module";
			TPointer moduleNameAddr = allocMem(moduleName.length() + 1);
			moduleNameAddr.setStringZ(moduleName);
			Modules.ThreadManForUserModule.executeCallback(thread, sceKernelFindModuleByName, new AfterSceKernelFindModuleByName(thread, moduleNameAddr), false, moduleNameAddr.getAddress());
    	}

    	return 0;
    }

    private void fixExistingModule(TPointer moduleNameAddr, int sceModuleAddr) {
    	String moduleName = moduleNameAddr.getStringZ();
    	Memory mem = moduleNameAddr.getMemory();
    	freeMem(moduleNameAddr);

    	if (sceModuleAddr != 0) {
    		SceModule sceModule = Managers.modules.getModuleByName(moduleName);
    		if (sceModule != null) {
    			// These values are required by the PSP implementation of
    			// sceKernelFindModuleByAddress().
    			mem.write32(sceModuleAddr + 108, sceModule.text_addr);
    			mem.write32(sceModuleAddr + 112, sceModule.text_size);
    			mem.write32(sceModuleAddr + 116, sceModule.data_size);
    			mem.write32(sceModuleAddr + 120, sceModule.bss_size);
    		}
    	}
    }

    private void onModuleStart() {
    	dummyModuleData = new HashSet<Integer>();
		SceLoadCoreBootInfo sceLoadCoreBootInfo = new SceLoadCoreBootInfo();
		SysMemThreadConfig sysMemThreadConfig = new SysMemThreadConfig();
		SceLoadCoreExecFileInfo loadCoreExecInfo = new SceLoadCoreExecFileInfo();
		SceLoadCoreExecFileInfo sysMemExecInfo = new SceLoadCoreExecFileInfo();
		PSPModuleInfo loadCoreModuleInfo = new PSPModuleInfo();
		PSPModuleInfo sysMemModuleInfo = new PSPModuleInfo();

		prepareKernelLibs(sysMemThreadConfig);

		// loadcore.prx is computing a checksum on the first 64 bytes of the first segment
		int dummySegmentSize = 64;
		TPointer dummySegmentAddr = allocMem(dummySegmentSize);
		loadCoreExecInfo.segmentAddr[0] = dummySegmentAddr;
		loadCoreExecInfo.segmentSize[0] = dummySegmentSize;
		loadCoreExecInfo.numSegments = 1;
		sysMemExecInfo.segmentAddr[0] = dummySegmentAddr;
		sysMemExecInfo.segmentSize[0] = dummySegmentSize;
		sysMemExecInfo.numSegments = 1;

		final int totalNumberOfModules = 2;
		SceLoadCoreBootModuleInfo sceLoadCoreBootModuleInfo = new SceLoadCoreBootModuleInfo();
		sceLoadCoreBootInfo.startAddr = allocMem(totalNumberOfModules * sceLoadCoreBootModuleInfo.sizeof());
		sceLoadCoreBootInfo.numModules = 0;

		addExistingModule(sceLoadCoreBootInfo, "scePaf_Module");

		// Add the "sceInit" module as the last one
		createDummyInitModule(sceLoadCoreBootModuleInfo);
		TPointer sceLoadCoreBootModuleInfoAddr = new TPointer(sceLoadCoreBootInfo.startAddr, sceLoadCoreBootInfo.numModules * sceLoadCoreBootModuleInfo.sizeof());
		sceLoadCoreBootModuleInfo.write(sceLoadCoreBootModuleInfoAddr);
		sceLoadCoreBootInfo.numModules++;

		TPointer loadCoreModuleInfoAddr = allocMem(loadCoreModuleInfo.sizeof());
		loadCoreModuleInfo.write(loadCoreModuleInfoAddr);
		loadCoreExecInfo.moduleInfo = loadCoreModuleInfoAddr;

		TPointer sysMemModuleInfoAddr = allocMem(sysMemModuleInfo.sizeof());
		sysMemModuleInfo.write(sysMemModuleInfoAddr);
		sysMemExecInfo.moduleInfo = sysMemModuleInfoAddr;

		TPointer sysMemExecInfoAddr = allocMem(sysMemExecInfo.sizeof());
		sysMemExecInfo.write(sysMemExecInfoAddr);
		sysMemThreadConfig.sysMemExecInfo = sysMemExecInfoAddr;

		TPointer loadCoreExecInfoAddr = allocMem(loadCoreExecInfo.sizeof());
		loadCoreExecInfo.write(loadCoreExecInfoAddr);
		sysMemThreadConfig.loadCoreExecInfo = loadCoreExecInfoAddr;

		TPointer sceLoadCoreBootInfoAddr = allocMem(sceLoadCoreBootInfo.sizeof());
		sceLoadCoreBootInfo.write(sceLoadCoreBootInfoAddr);

		TPointer sysMemThreadConfigAddr = allocMem(sysMemThreadConfig.sizeof());
		sysMemThreadConfig.write(sysMemThreadConfigAddr);

    	TPointer argp = allocMem(8);
		argp.setPointer(0, sceLoadCoreBootInfoAddr);
		argp.setPointer(4, sysMemThreadConfigAddr);

		if (log.isDebugEnabled()) {
			log.debug(String.format("onModuleStart sceLoadCoreBootInfoAddr=%s, sysMemThreadConfigAddr=%s, loadCoreExecInfoAddr=%s, sysMemExecInfoAddr=%s", sceLoadCoreBootInfoAddr, sysMemThreadConfigAddr, loadCoreExecInfoAddr, sysMemExecInfoAddr));
		}

		// Set the thread start parameters
    	SceKernelThreadInfo currentThread = Modules.ThreadManForUserModule.getCurrentThread();
    	currentThread.cpuContext._a0 = 8;
    	currentThread.cpuContext._a1 = argp.getAddress();
    }

    private int scanForLoadConstantValueIntoRegister(Memory mem, int addr, int reg) {
    	int value = 0;
		int baseRegister = -1;
		int offsetLow = 0;
		// Search backwards for such a sequence of instructions:
		//    lui    $baseRegister, offsetHigh
		//    ...
		//    addiu  $reg, $baseRegister, offsetLow
		//    ...
		//
		// The value is then
		//    (offsetHigh << 16) + offsetLow
		//
		for (int i = 4; i < 300; i += 4) {
			int opcode = mem.internalRead32(addr - i);
			if ((opcode >>> 26) == ADDIU && ((opcode >> 16) & 0x1F) == reg) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Found addiu at 0x%08X", addr - i));
				}
				baseRegister = (opcode >> 21) & 0x1F;
				offsetLow = opcode & 0xFFFF;
			} else if ((opcode >>> 26) == LUI && ((opcode >> 16) & 0x1F) == baseRegister) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Found lui at 0x%08X", addr - i));
				}
				int offsetHigh = opcode & 0xFFFF;
				value = (offsetHigh << 16) + (short) offsetLow;
				if (log.isDebugEnabled()) {
					log.debug(String.format("Found constant value=0x%08X", value));
				}
				break;
			}
		}

		return value;
    }

    private int getLoadCoreBaseAddress() {
    	// If the loadCodeBaseAddress has not yet been identified,
    	// scan for the initialization
    	//     g_loadCore.registeredMods = NULL;
    	// in the loadCoreInit() function.
    	// See https://github.com/uofw/uofw/blob/master/src/loadcore/loadcore.c
    	if (loadCoreBaseAddress == 0) {
			Memory mem = Memory.getInstance();
			if (log.isTraceEnabled()) {
				log.trace("Searching for the loadCodeBaseAddress");
			}

    		//
    		// Search for such a sequence of instructions:
    		//    lui    $baseRegister, offsetHigh
    		//    ...
    		//    addiu  $a0, $baseRegister, offsetLow
    		//    ...
    		//    sw     $zr, 524($a0)
    		//
    		// The loadCodeBaseAddress is then
    		//    (offsetHigh << 16) + offsetLow
    		//
    		// First search for the 3rd instruction
			int swOpcode = SW(_zr, _a0, 524); // sw $zr, 524($a0)
			if (getFirmwareVersion() <= 201) {
				swOpcode = SW(_zr, _a1, 20); // sw $zr, 20($a1)
			}
			for (int i = 0x13000; i < 0x18000 && loadCoreBaseAddress == 0; i += 4) {
				int addr = MemoryMap.START_KERNEL + i;
				if (mem.internalRead32(addr) == swOpcode) {
					if (log.isDebugEnabled()) {
						log.debug(String.format("Found sw at 0x%08X", addr));
					}
					int register = (swOpcode >> 21) & 0x1F;
    				// Now, search backwards for the 1st and 2nd instructions
					loadCoreBaseAddress = scanForLoadConstantValueIntoRegister(mem, addr, register);
					if (log.isDebugEnabled()) {
						log.debug(String.format("Found loadCoreBaseAddress=0x%08X", loadCoreBaseAddress));
					}
				}
			}
    	}

    	return loadCoreBaseAddress;
    }

    private int getSyscallBaseAddress() {
    	// If the syscallBaseAddress has not yet been identified,
    	// scan the interrupt function.
    	if (syscallBaseAddress == 0) {
    		Memory mem = Memory.getInstance();
    		int ebase = RuntimeContextLLE.getProcessor().cp0.getEbase();
    		if (log.isDebugEnabled()) {
    			log.debug(String.format("Found ebase=0x%08X", ebase));
    		}

    		//
    		// Search for such a sequence of instructions located at the address from the ebase register:
    		//    j      label
    		//    ...
    		// label:
    		//    lui    $baseRegister, offsetHigh
    		//    ...
    		//    lw     $jumpRegister, offsetLow($baseRegister)
    		//    ...
    		//    jr     $jumpRegister
    		//
    		// The syscallBaseAddress is then
    		//    (offsetHigh << 16) + offsetLow
    		//
    		// First search for the 1st instruction
    		for (int i = 0; i < 100; i += 4) {
    			int opcode = mem.internalRead32(ebase + i);
    			if ((opcode >>> 26) == J) {
    				int addr = (ebase & 0xF0000000) | ((opcode & 0x3FFFFFF) << 2);
    				if (log.isDebugEnabled()) {
    					log.debug(String.format("Found jump instruction to 0x%08X at 0x%08X", addr, ebase + i));
    				}
    				// Then, from the jump address, search for the 4th instruction
    				for (int j = 0; j < 100 && syscallBaseAddress == 0; j += 4) {
    					opcode = mem.internalRead32(addr + j);
    					if ((opcode >>> 26) == SPECIAL && (opcode & 0x3F) == JR) {
    	    				if (log.isDebugEnabled()) {
    	    					log.debug(String.format("Found jr instruction at 0x%08X", addr + j));
    	    				}
    						int jumpRegister = (opcode >> 21) & 0x1F;
    						int offsetLow = 0;
    						int baseRegister = -1;
    						// Now, search backwards for the 2nd and 3rd instructions
    						for (int k = 4; k < 100 && syscallBaseAddress == 0; k += 4) {
    							opcode = mem.internalRead32(addr + j - k);
    							if ((opcode >>> 26) == LW && ((opcode >> 16) & 0x1F) == jumpRegister) {
    								if (log.isDebugEnabled()) {
    									log.debug(String.format("Found lw at 0x%08X", addr + j - k));
    								}
    								baseRegister = (opcode >> 21) & 0x1F;
    								offsetLow = opcode & 0xFFFF;
    							} else if ((opcode >>> 26) == LUI && ((opcode >> 16) & 0x1F) == baseRegister) {
    								if (log.isDebugEnabled()) {
    									log.debug(String.format("Found lui at 0x%08X", addr + j - k));
    								}
    								int offsetHigh = opcode & 0xFFFF;
    								syscallBaseAddress = (offsetHigh << 16) + (short) offsetLow;
    	    						if (log.isDebugEnabled()) {
    	    							log.debug(String.format("Found syscallBaseAddress=0x%08X", syscallBaseAddress));
    	    						}
    							}
    						}
    					}
    				}
    			}
    		}
    	}

    	return syscallBaseAddress;
    }

    private int getRegisteredModules(Memory mem) {
    	int g_loadCore = getLoadCoreBaseAddress();
    	if (g_loadCore == 0) {
    		return 0;
    	}

    	int registeredModulesOffset;
    	if (getFirmwareVersion() <= 201) {
    		registeredModulesOffset = 20;
    	} else if (getFirmwareVersion() < 300) {
    		registeredModulesOffset = 528;
    	} else {
    		registeredModulesOffset = 524;
    	}

    	int registeredModules = mem.internalRead32(g_loadCore + registeredModulesOffset);

    	return registeredModules;
    }

    public int getThreadManInfo() {
    	// If the threadManInfo has not yet been identified,
    	// scan the sceThreadManager.module_bootstart() function.
    	if (threadManInfo == 0) {
        	Memory mem = Memory.getInstance();
        	int registeredMods = getRegisteredModules(mem);
        	if (registeredMods != 0) {
    			if (log.isDebugEnabled()) {
    				log.debug("Searching for the threadManInfo");
    			}
            	int threadManModule = getModuleByName(mem, registeredMods, "sceThreadManager");
            	if (threadManModule != 0) {
            		int moduleBootStart = mem.internalRead32(threadManModule + 88);
            		if (log.isDebugEnabled()) {
            			log.debug(String.format("Found sceThreadManager.module_bootstart=0x%08X", moduleBootStart));
            		}

            		//
            		// Search for such a sequence of instructions:
            		//    lui    $baseRegister, offsetHigh
            		//    ...
            		//    addiu  $valueRegister, $baseRegister, offsetLow
            		//    ...
            		//    addu   $a0, $valueRegister, $zr
            		//
            		// The threadManInfo is then
            		//    (offsetHigh << 16) + offsetLow
            		//
            		// First search for the 3rd instruction
            		for (int i = 0; i < 200 && threadManInfo == 0; i += 4) {
            			int addr = moduleBootStart + i;
            			int opcode = mem.internalRead32(addr);
            			if ((opcode >>> 26) == SPECIAL && (opcode & 0x3F) == ADDU && ((opcode >> 11) & 0x1F) == _a0 && ((opcode >> 16) & 0x1F) == _zr) {
            				int valueRegister = (opcode >> 21) & 0x1F;
            				if (log.isDebugEnabled()) {
            					log.debug(String.format("Found addu at 0x%08X", addr));
            				}
            				// Now, search backwards for the 1st and 2nd instructions
            				threadManInfo = scanForLoadConstantValueIntoRegister(mem, addr, valueRegister);
        					if (log.isDebugEnabled()) {
        						log.debug(String.format("Found threadManInfo=0x%08X", threadManInfo));
        					}

        					// Search for "addiu $reg, $valueRegister, offset" instructions
        					int lastOffset = addr;
        					int[] offsets = null;
        					final int wantedNumberOfOffsets = 6;
        					for (int j = 0; j < 200; j += 4) {
        						opcode = mem.internalRead32(addr + j);
        						if ((opcode >>> 26) == ADDIU && ((opcode >> 21) & 0x1F) == valueRegister) {
        							int offset = (short) (opcode & 0xFFFF);
        							if (log.isDebugEnabled()) {
        								log.debug(String.format("Found addiu with offset %d at 0x%08X", offset, addr + j));
        							}
        							offsets = Utilities.add(offsets, offset);
        							lastOffset = addr + j;

        							if (offsets.length >= wantedNumberOfOffsets) {
        								break;
        							}
        						}
        					}

        					if (offsets != null && offsets.length >= wantedNumberOfOffsets) {
        						// Sort the offsets, as they are always stored in the same order
        						Arrays.sort(offsets);
        						int n = 0;
        						threadManInfoReadyThreadsOffset = offsets[n++];
        						threadManInfoSleepingThreadsOffset = offsets[n++];
        						threadManInfoDelayedThreadsOffset = offsets[n++];
        						threadManInfoStoppedThreadsOffset = offsets[n++];
        						threadManInfoSuspendedThreadsOffset = offsets[n++];
        						threadManInfoDeadThreadsOffset = offsets[n++];
        						// Not sure at which FW version this offset has been introduced
        						if (getFirmwareVersion() >= 660) {
        							threadManInfoUnknownThreadsOffset = threadManInfoDeadThreadsOffset + 8;
        						} else {
        							threadManInfoUnknownThreadsOffset = -1;
        						}

        						if (log.isDebugEnabled()) {
        							log.debug(String.format("Found threadManInfoReadyThreadsOffset=%d", threadManInfoReadyThreadsOffset));
        							log.debug(String.format("Found threadManInfoSleepingThreadsOffset=%d", threadManInfoSleepingThreadsOffset));
        							log.debug(String.format("Found threadManInfoDelayedThreadsOffset=%d", threadManInfoDelayedThreadsOffset));
        							log.debug(String.format("Found threadManInfoStoppedThreadsOffset=%d", threadManInfoStoppedThreadsOffset));
        							log.debug(String.format("Found threadManInfoSuspendedThreadsOffset=%d", threadManInfoSuspendedThreadsOffset));
        							log.debug(String.format("Found threadManInfoDeadThreadsOffset=%d", threadManInfoDeadThreadsOffset));
        							log.debug(String.format("Found threadManInfoUnknownThreadsOffset=%d", threadManInfoUnknownThreadsOffset));
        						}
        					}

        					threadManInfoThreadTypeOffset = -1;
        					// Search for "addiu $a1, $zr, 2028" instruction
        					opcode = HLEUtilities.ADDIU(_a1, _zr, 2048);
        					for (int j = 0; j < 200; j += 4) {
        						if (mem.internalRead32(lastOffset + j) == opcode) {
        							// Search for "lw $a0, threadManInfoThreadTypeOffset($reg)" instruction
        							for (int k = 0; k < 200; k += 4) {
        								opcode = mem.internalRead32(lastOffset + j + k);
        								if ((opcode >>> 26) == LW && ((opcode >> 16) & 0x1F) == _a0) {
        									threadManInfoThreadTypeOffset = (short) (opcode & 0xFFFF);
        									if (log.isDebugEnabled()) {
        										log.debug(String.format("Found threadManInfoThreadTypeOffset=%d", threadManInfoThreadTypeOffset));
        									}
        									break;
        								}
        							}
        							break;
        						}
        					}
            			}
            		}
            	}
        	}
    	}

    	return threadManInfo;
    }

    public HLEModuleFunction getHLEFunctionByAddress(int address) {
    	if (!reboot.enableReboot) {
    		return null;
    	}

    	address &= Memory.addressMask;

    	Memory mem = Memory.getInstance();
    	int g_loadCore = getLoadCoreBaseAddress();
    	if (g_loadCore == 0) {
    		return null;
    	}
    	int registeredLibs = g_loadCore + 0;

    	int[] nids = getFunctionNIDsByAddress(mem, registeredLibs, address);
    	if (nids == null) {
    		if (Memory.isAddressGood(address)) {
	    		// Verify if this not the address of a stub call:
	    		//   J   realAddress
	    		//   NOP
	        	if ((mem.internalRead32(address) >>> 26) == J) {
	        		if (mem.internalRead32(address + 4) == NOP()) {
	        			int jumpAddress = (mem.internalRead32(address) & 0x03FFFFFF) << 2;

	        			nids = getFunctionNIDsByAddress(mem, registeredLibs, jumpAddress);
	        		}
	        	}
    		}
    	}

    	if (nids != null) {
    		for (int nid : nids) {
        		HLEModuleFunction hleFunction = HLEModuleManager.getInstance().getFunctionFromNID(nid);
        		if (hleFunction != null) {
        			return hleFunction;
        		}
    		}
    	}

    	return null;
    }

    public String getFunctionNameBySyscall(Memory mem, int syscallCode) {
    	if (!reboot.loadCoreInitialized) {
    		return null;
    	}

		int address = 0;
		int syscallIndex = syscallCode << 2;
		if (syscallIndex < 256) {
			int syscallBaseAddres = getSyscallBaseAddress();
			if (syscallBaseAddres == 0) {
				return null;
			}
			address = mem.read32(syscallBaseAddres + syscallIndex);
		} else {
			int syscallTable = Emulator.getProcessor().cp0.getSyscallTable();
			while (syscallTable != 0) {
				int baseSyscallIndex = mem.read32(syscallTable + 4);
				int tableSize = mem.read32(syscallTable + 8);
				if (syscallIndex >= baseSyscallIndex && syscallIndex < baseSyscallIndex + tableSize) {
					address = mem.read32(syscallTable + 16 + syscallIndex - baseSyscallIndex);
					break;
				}
				syscallTable = mem.read32(syscallTable + 0);
			}
		}

		return getFunctionNameByAddress(mem, address);
    }

    public String getFunctionNameByAddress(Memory mem, int address) {
    	if (!reboot.loadCoreInitialized || (address & Memory.addressMask) == 0) {
    		return null;
    	}

    	String functionName = null;

		int nextOpcode = Emulator.getMemory(address).internalRead32(address + 4);
		Instruction nextInsn = Decoder.instruction(nextOpcode);
		if (nextInsn == Instructions.SYSCALL) {
			int syscallCode = (nextOpcode >> 6) & 0xFFFFF;
			functionName = getFunctionNameBySyscall(mem, syscallCode);
			if (functionName != null) {
				return functionName;
			}
		}

    	HLEModuleFunction hleModuleFunction = getHLEFunctionByAddress(address);
    	if (hleModuleFunction != null) {
    		return hleModuleFunction.getFunctionName();
    	}

    	int registeredMods = getRegisteredModules(mem);
    	if (registeredMods == 0) {
    		return null;
    	}

    	address &= Memory.addressMask;
    	int module = getModuleByAddress(mem, registeredMods, address);
    	if (module != 0) {
    		String moduleName = Utilities.readInternalStringNZ(mem, module + 8, 27);
			int moduleStart = mem.internalRead32(module + 80) & Memory.addressMask;
			int moduleStop = mem.internalRead32(module + 84) & Memory.addressMask;
			int moduleBootStart = mem.internalRead32(module + 88) & Memory.addressMask;
			int moduleRebootBefore = mem.internalRead32(module + 92) & Memory.addressMask;
			int moduleRebootPhase = mem.internalRead32(module + 96) & Memory.addressMask;
			int entryAddr = mem.internalRead32(module + 100) & Memory.addressMask;
			int textAddr = mem.internalRead32(module + 108) & Memory.addressMask;

    		if (address == moduleStart) {
    			functionName = String.format("%s.module_start", moduleName);
    		} else if (address == moduleStop) {
    			functionName = String.format("%s.module_stop", moduleName);
    		} else if (address == moduleBootStart) {
    			functionName = String.format("%s.module_bootstart", moduleName);
    		} else if (address == moduleRebootBefore) {
    			functionName = String.format("%s.module_reboot_before", moduleName);
    		} else if (address == moduleRebootPhase) {
    			functionName = String.format("%s.module_reboot_phase", moduleName);
    		} else if (address == entryAddr) {
    			functionName = String.format("%s.module_start", moduleName);
    		} else {
    			functionName = String.format("%s.sub_%08X", moduleName, address - textAddr);
    		}
    	}

    	if (functionName != null && functionNames.containsKey(functionName)) {
    		functionName = functionNames.get(functionName);
    	}

    	return functionName;
    }

    public void addFunctionName(String moduleName, int address, String functionName) {
    	functionNames.put(String.format("%s.sub_%08X", moduleName, address), functionName);
    }

    public void addFunctionNid(int address, int nid) {
    	address &= Memory.addressMask;

    	functionNids.put(address, nid);
    }

    private int[] getFunctionNIDsByAddress(Memory mem, int registeredLibs, int address) {
    	int[] nids = null;

    	final int firmwareVersion = RuntimeContextLLE.getFirmwareVersion();
    	final int registeredLibsEntries = firmwareVersion <= 201 ? 1 : 128;
		for (int i = 0; i < registeredLibsEntries; i++) {
			int linkedLibraries = mem.internalRead32(registeredLibs + i * 4);
	    	while (linkedLibraries != 0 && Memory.isAddressGood(linkedLibraries)) {
	    		int numExports;
	    		int entryTable;
	    		if (firmwareVersion < 260) {
		    		numExports = mem.internalRead16(linkedLibraries + 14);
		    		entryTable = mem.internalRead32(linkedLibraries + 16);
	    		} else {
	    			numExports = mem.internalRead32(linkedLibraries + 16);
		    		entryTable = mem.internalRead32(linkedLibraries + 32);
	    		}

	    		// Sanity checks
	    		if (Memory.isAddressGood(entryTable) && numExports <= 1000) {
		    		for (int j = 0; j < numExports; j++) {
		    			int nid = mem.internalRead32(entryTable + j * 4);
		    			int entryAddress = mem.internalRead32(entryTable + (j + numExports) * 4) & Memory.addressMask;

		    			if (address == entryAddress) {
		    				nids = Utilities.add(nids, nid);
		    			}
		    		}
	    		}
	
	    		// Next
	    		linkedLibraries = mem.internalRead32(linkedLibraries);
	    	}
		}

		if (nids == null && functionNids.containsKey(address)) {
			nids = new int[] { functionNids.get(address).intValue() };
		}

		return nids;
    }

    private int getModuleByName(Memory mem, int linkedModules, String name) {
    	while (linkedModules != 0 && Memory.isAddressGood(linkedModules)) {
    		String moduleName = Utilities.readInternalStringNZ(mem, linkedModules + 8, 27);

    		if (name.equals(moduleName)) {
    			return linkedModules;
    		}

    		// Next
    		linkedModules = mem.internalRead32(linkedModules + 0);
    	}

    	return 0;
    }

    private int getModuleByAddress(Memory mem, int linkedModules, int address) {
    	while (linkedModules != 0 && Memory.isAddressGood(linkedModules)) {
    		int textAddr = mem.internalRead32(linkedModules + 108) & Memory.addressMask;
    		int textSize = mem.internalRead32(linkedModules + 112);

    		if (textAddr <= address && address < textAddr + textSize) {
    			return linkedModules;
    		}

    		// Next
    		linkedModules = mem.internalRead32(linkedModules + 0);
    	}

    	return 0;
    }

    @HLEUnimplemented
	@HLEFunction(nid = 0xACE23476, version = 150)
	public int sceKernelCheckPspConfig() {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0xB4D6FECC, version = 150)
	public int sceKernelApplyElfRelSection() {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0x54AB2675, version = 150)
	public int sceKernelApplyPspRelSection() {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0x2952F5AC, version = 150)
	public int sceKernelDcacheWBinvAll() {
		return 0;
	}

    @HLELogging(level="trace")
    @HLEFunction(nid = 0xD8779AC6, version = 150)
    public int sceKernelIcacheClearAll() {
        return 0;
    }

    @HLEUnimplemented
	@HLEFunction(nid = 0xC99DD47A, version = 150)
	public int sceKernelQueryLoadCoreCB() {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0x616FCCCD, version = 150)
	public int sceKernelSetBootCallbackLevel() {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0xAFF947D4, version = 150)
	public int sceKernelCreateAssignModule() {
		return 0;
	}

	/**
	 * Register a module in the system and link it into the internal loaded-modules-linked-list.
	 * 
	 * @param module The module to register.
	 * 
	 * @return 0.
	 */
    @HLEUnimplemented
	@HLEFunction(nid = 0xAE7C6E76, version = 150)
	@HLEFunction(nid = 0xBF2E388C, version = 660)
	public int sceKernelRegisterModule(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=228, usage=Usage.inout) TPointer module) {
		return 0;
	}

	/**
	 * Find a loaded module by its name. If more than one module with the same name is loaded, return 
	 * the module which was loaded last.
	 * 
	 * @param name The name of the module to find. 
	 * 
	 * @return Pointer to the found SceModule structure on success, otherwise NULL.
	 */
    @HLEFunction(nid = 0xCF8A41B1, version = 150)
	@HLEFunction(nid = 0xF6B1BF0F, version = 660)
    public int sceKernelFindModuleByName(PspString moduleName) {
        SceModule module = Managers.modules.getModuleByName(moduleName.getString());
        if (module == null) {
            log.warn(String.format("sceKernelFindModuleByName not found moduleName=%s", moduleName));
            return 0; // return NULL
        }

        if (!Modules.ThreadManForUserModule.isKernelMode()) {
            log.warn("kernel mode required (sceKernelFindModuleByName)");
        }

        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceKernelFindModuleByName returning 0x%08X", module.address));
        }

        return module.address;
    }

	/**
	 * Find a loaded module containing the specified address.
	 * 
	 * @param addr Memory address belonging to the module, i.e. the address of a function/global variable 
	 *             within the module.
	 * 
	 * @return Pointer to the found SceModule structure on success, otherwise NULL.
	 */
    @HLEFunction(nid = 0xFB8AE27D, version = 150)
	@HLEFunction(nid = 0xBC99C625, version = 660)
    public int sceKernelFindModuleByAddress(TPointer address) {
        SceModule module = Managers.modules.getModuleByAddress(address.getAddress());
        if (module == null) {
            log.warn(String.format("sceKernelFindModuleByAddress not found module address=%s", address));
            return 0; // return NULL
        }

        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceKernelFindModuleByAddress found module '%s'", module.modname));
        }

        if (!Modules.ThreadManForUserModule.isKernelMode()) {
            log.warn("kernel mode required (sceKernelFindModuleByAddress)");
        }

        return module.address;
	}

	/**
	 * Find a loaded module by its UID.
	 * 
	 * @param uid The UID of the module to find.
	 * 
	 * @return Pointer to the found SceModule structure on success, otherwise NULL.
	 */
    @HLEFunction(nid = 0xCCE4A157, version = 150)
	@HLEFunction(nid = 0x40972E6E, version = 660)
    public int sceKernelFindModuleByUID(int uid) {
        SceModule module = Managers.modules.getModuleByUID(uid);
        if (module == null) {
            log.warn(String.format("sceKernelFindModuleByUID not found module uid=0x%X", uid));
            return 0; // return NULL
        }

        // The pspsdk is not properly handling module exports with a size > 4.
        // See
        //    pspSdkFindExport()
        // in
        //    https://github.com/pspdev/pspsdk/blob/master/src/sdk/fixup.c
        // which is assuming that all module exports have a size==4 (i.e. 16 bytes).
        // This code is leading to an invalid memory access when processing the exports
        // from real PSP modules, which do have exports with a size==5.
        // Ban these modules in a case of the homebrew.
        if (RuntimeContext.isHomebrew()) {
        	String[] bannedModules = {
        			"sceNet_Library",
        			"sceNetInet_Library",
        			"sceNetApctl_Library",
        			"sceNetResolver_Library"
        	};
        	for (String bannedModule : bannedModules) {
        		if (bannedModule.equals(module.modname)) {
        			if (log.isDebugEnabled()) {
        				log.debug(String.format("sceKernelFindModuleByUID banning module '%s' for a homebrew", module.modname));
        			}
            		return 0; // NULL
        		}
        	}
        }

        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceKernelFindModuleByUID found module '%s'", module.modname));
        }

        if (!Modules.ThreadManForUserModule.isKernelMode()) {
            log.warn("kernel mode required (sceKernelFindModuleByUID)");
        }

        return module.address;
    }

    @HLEUnimplemented
	@HLEFunction(nid = 0xB27CC244, version = 150)
	public int sceKernelLoadRebootBin(@BufferInfo(lengthInfo=LengthInfo.nextParameter, usage=Usage.in) TPointer fileData, int fileSize) {
		return 0;
	}

    /**
     * Load a module. This function is used to boot modules during the start of Loadcore. In order for 
     * a module to be loaded, it has to be a kernel module.
     * 
     * @param bootModInfo Pointer to module information (including the file content of the module, 
     *                    its size,...) used to boot the module.
     * @param execInfo Pointer an allocated execInfo structure used to handle load-checks against the 
     *                 program module.
     *                 Furthermore, it collects various information about the module, such as its elfType, 
     *                 its segments (.text, .data, .bss), the locations of its exported functions.
     * @param modMemId The memory id of the allocated kernelPRX memory block used for the program module 
     *                 sections. The memory block specified by the ID holds the .text segment of the module. 
     * 
     * @return 0 on success.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x493EE781, version = 660)
	public int sceKernelLoadModuleBootLoadCore(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=32, usage=Usage.in) TPointer bootModInfo, @BufferInfo(lengthInfo=LengthInfo.fixedLength, length=192, usage=Usage.out) TPointer execInfo, @BufferInfo(usage=Usage.out) TPointer32 modMemId) {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0x7BE1421C, version = 150)
	@HLEFunction(nid = 0xD3353EC4, version = 660)
	public int sceKernelCheckExecFile(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=256, usage=Usage.in) TPointer buf, @BufferInfo(lengthInfo=LengthInfo.fixedLength, length=192, usage=Usage.inout) TPointer execInfo) {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0xBF983EF2, version = 150)
	@HLEFunction(nid = 0x41D10899, version = 660)
	public int sceKernelProbeExecutableObject(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=256, usage=Usage.in) TPointer buf, @BufferInfo(lengthInfo=LengthInfo.fixedLength, length=192, usage=Usage.inout) TPointer execInfo) {
		return 0;
	}

    @HLEUnimplemented
	@HLEFunction(nid = 0x7068E6BA, version = 150)
	@HLEFunction(nid = 0x1C394885, version = 660)
	public int sceKernelLoadExecutableObject(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=256, usage=Usage.in) TPointer buf, @BufferInfo(lengthInfo=LengthInfo.fixedLength, length=192, usage=Usage.inout) TPointer execInfo) {
		return 0;
	}

    /**
     * Register a resident library's entry table in the system. A resident module can register any 
     * number of resident libraries. Note that this function is only meant to register kernel mode 
     * resident libraries. In order to register user mode libraries, use sceKernelRegisterLibraryForUser().
     * 
     * @param libEntryTable Pointer to the resident library's entry table.
     * 
     * @return 0 on success.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x99A695F0, version = 150)
	@HLEFunction(nid = 0x48AF96A9, version = 660)
	public int sceKernelRegisterLibrary(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=20, usage=Usage.in) TPointer libEntryTable) {
		return 0;
	}

    /**
     * Check if a resident library can be released. This check returns "true" when all corresponding stub
     * libraries at the time of the check have one the following status:
     *      a) unlinked
     *      b) have the the attribute SCE_LIB_WEAK_IMPORT (they can exist without the resident library 
     *         being registered).
     * 
     * @param libEntryTable Pointer to the resident library's entry table.
     * 
     * @return 0 indicates the library can be released.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x9BAF90F6, version = 150)
	@HLEFunction(nid = 0x538129F8, version = 660)
	public int sceKernelCanReleaseLibrary(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=20, usage=Usage.in) TPointer libEntryTable) {
		return 0;
	}

    /**
     * Link kernel mode stub libraries with the corresponding registered resident libraries. Note that 
     * this function assumes that the resident libraries linked with reside in kernel memory. Linking 
     * with user mode resident libraries will result in failure.
     * 
     * @param libStubTable Pointer to a stub library's entry table. If you want to link an array of 
     *                     entry tables, make libStubTable a pointer to the first element of that array.
     * @param size         The number of entry tables to link.
     * 
     * @return 0 on success.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x0E760DBA, version = 150)
	@HLEFunction(nid = 0x8EAE9534, version = 660)
	public int sceKernelLinkLibraryEntries(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=26, usage=Usage.in) TPointer libEntryTable, int size) {
		return 0;
	}

    /**
     * Unlink stub libraries from their corresponding registered resident libraries. 
     * 
     * @param libStubTable Pointer to a stub library's entry table. If you want to unlink an array of 
     *                     entry tables, make libStubTable a pointer to the first element of that array.
     * @param size The number of entry tables to unlink.
     * @return 
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0xDA1B09AA, version = 150)
	@HLEFunction(nid = 0x0295CFCE, version = 660)
	public int sceKernelUnLinkLibraryEntries(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=26, usage=Usage.in) TPointer libEntryTable, int size) {
		return 0;
	}

    /**
     * Save interrupts state and disable all interrupts.
     * 
     * @return The current state of the interrupt controller. Use sceKernelLoadCoreUnlock() to return 
     *         to that state.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x1999032F, version = 660)
	public int sceKernelLoadCoreLock() {
    	// Has no parameters
		return 0;
	}

    /**
     * Return interrupt state.
     * 
     * @param intrState The state acquired by sceKernelLoadCoreLock().
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0xB6C037EA, version = 660)
	public int sceKernelLoadCoreUnlock(int intrState) {
		return 0;
	}

    /**
     * Register a user mode resident library's entry table in the system. A resident module can register 
     * any number of resident libraries. In order to register kernel mode libraries, use 
     * sceKernelRegisterLibrary().
     * 
     * Restrictions on user mode resident libraries:
     *    1) The resident library has to live in user memory.
     *    2) Functions cannot be exported via the SYSCALL technique.
     *    3) The resident library cannot be linked with stub libraries living in kernel memory.
     * 
     * @param libEntryTable Pointer to the resident library's entry table.
     * 
     * @return 0 on success.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x5873A31F, version = 150)
	@HLEFunction(nid = 0x2C60CCB8, version = 660)
	public int sceKernelRegisterLibraryForUser(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=26, usage=Usage.in) TPointer libEntryTable) {
		return 0;
	}

    /**
     * Delete a registered resident library from the system. Deletion cannot be performed if there are 
     * loaded modules using the resident library. These modules must be deleted first.
     * 
     * @param libEntryTable Pointer to the resident library's entry table.
     * 
     * @return 0 on success.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x0B464512, version = 150)
	@HLEFunction(nid = 0xCB636A90, version = 660)
	public int sceKernelReleaseLibrary(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=26, usage=Usage.in) TPointer libEntryTable) {
		return 0;
	}

    /**
     * 
     * @param libStubTable Pointer to a stub library's entry table. If you want to link an array of entry 
     *                     tables, make libStubTable a pointer to the first element of that array.
     * @param size The number of entry tables to link.
     * 
     * @return 0 on success.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0x0DE1F600, version = 150)
	@HLEFunction(nid = 0x6ECFFFBA, version = 660)
	public int sceKernelLinkLibraryEntriesForUser(@BufferInfo(lengthInfo=LengthInfo.nextParameter, usage=Usage.in) TPointer libStubTable, int size) {
		return 0;
	}

    /**
     * 
     * @param libStubTable Pointer to a stub library's entry table. If you want to link an array of entry 
     *                     tables, make libStubTable a pointer to the first element of that array.
     * @param size The number of entry tables to link.
     * 
     * @return 0 on success.
     */
    @HLEUnimplemented
	@HLEFunction(nid = 0xA481E30E, version = 660)
	public int sceKernelLinkLibraryEntriesWithModule(TPointer mod, @BufferInfo(lengthInfo=LengthInfo.nextParameter, usage=Usage.in) TPointer libStubTable, int size) {
		return 0;
	}

    /**
     * Does nothing but a simple return.
     * 
     * @return 0.
     */
	@HLEFunction(nid = 0x1915737F, version = 660)
	public int sceKernelMaskLibraryEntries() {
    	// Has no parameters
		return 0;
	}

	/**
	 * Delete a module from the system. The module has to be stopped and released before.
	 * 
	 * @param mod The module to delete.
	 * 
	 * @return 0 on success.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0x6B2371C2, version = 150)
	@HLEFunction(nid = 0x001B57BB, version = 660)
	public int sceKernelDeleteModule(TPointer mod) {
		return 0;
	}

	/**
	 * Allocate memory for a new SceModule structure and fill it with default values. This function is 
	 * called during the loading process of a module.
	 * 
	 * @return A pointer to the allocated SceModule structure on success, otherwise NULL.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0xCD0F3BAC, version = 150)
	@HLEFunction(nid = 0x2C44F793, version = 660)
	public int sceKernelCreateModule() {
		// Has no parameters
		return 0;
	}

	/**
	 * Receive a list of UIDs of loaded modules.
	 * 
	 * @param modIdList Pointer to a SceUID array which will receive the UIDs of the loaded modules.
	 * @param size Size of modIdList. Specifies the number of entries that can be stored into modIdList.
	 * @param modCount A pointer which will receive the total number of loaded modules.
	 * @param userModsOnly Set to 1 to only receive UIDs from user mode modules. Set to 0 to receive UIDs 
	 *                     from all loaded modules.
	 * 
	 * @return 0 on success.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0x05D915DB, version = 150)
	@HLEFunction(nid = 0x37E6F41B, version = 660)
	public int sceKernelGetModuleIdListForKernel(TPointer32 modIdList, int size, TPointer32 modCount, boolean userModsOnly) {
		return 0;
	}

	/**
	 * Receive a list of UIDs of all loaded modules.
	 * 
	 * @param modCount A pointer which will receive the total number of loaded modules.
	 * 
	 * @return The UID of the allocated array containing UIDs of the loaded modules on success. It should 
	 *        be greater than 0.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0x929B5C69, version = 150)
	@HLEFunction(nid = 0x3FE631F0, version = 660)
	public int sceKernelGetModuleListWithAlloc(TPointer32 modCount) {
		return 0;
	}

	/**
	 * Get the global pointer value of a module.
	 * 
	 * @param addr Memory address belonging to the module, i.e. the address of a function/global variable 
	 *             within the module.
	 * 
	 * @return The global pointer value (greater than 0) of the found module on success.
	 */
	@HLEFunction(nid = 0x410084F9, version = 660)
	public int sceKernelGetModuleGPByAddressForKernel(int addr) {
		return Managers.modules.getModuleGpByAddress(addr);
	}

	/**
	 * Compute a checksum of every segment of a module.
	 * 
	 * @param mod The module to create the checksum for.
	 * 
	 * @return The checksum. Shouldn't be 0.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0x5FDDB07A, version = 660)
	public int sceKernelSegmentChecksum(TPointer mod) {
		return 0;
	}

	/**
	 * Unlink a module from the internal loaded-modules-linked-list. The module has to be stopped before.
	 * 
	 * @param mod The module to release.
	 * 
	 * @return 0 on success.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0x74CF001A, version = 150)
	@HLEFunction(nid = 0xB17F5075, version = 660)
	public int sceKernelReleaseModule(TPointer mod) {
		return 0;
	}

	/**
	 * Get a loaded module from its UID.
	 * 
	 * @param uid The UID (of a module) to check for.
	 * 
	 * @return Pointer to the found SceModule structure on success, otherwise NULL.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0x52A86C21, version = 150)
	@HLEFunction(nid = 0xCD26E0CA, version = 660)
	public int sceKernelGetModuleFromUID(int uid) {
		return 0;
	}

	/**
	 * Assign a module and check if it can be loaded, is a valid module and copy the moduleInfo section 
	 * of the execution file over to the SceModule structure.
	 * 
	 * @param mod The module to receive the moduleInfo section data based on the provided execution file 
	 *            information.
	 * @param execFileInfo The execution file information used to copy over the moduleInfo section for 
	 *        the specified module.
	 * 
	 * @return 0 on success.
	 */
	@HLEUnimplemented
	@HLEFunction(nid = 0x8D8A8ACE, version = 150)
	@HLEFunction(nid = 0xF3DD4808, version = 660)
	public int sceKernelAssignModule(TPointer mod, @BufferInfo(lengthInfo=LengthInfo.fixedLength, length=192, usage=Usage.inout) TPointer execFileInfo) {
		return 0;
	}
}