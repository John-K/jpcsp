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
package jpcsp.HLE.kernel.managers;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import jpcsp.Emulator;
import jpcsp.Allegrex.compiler.RuntimeContextLLE;
import jpcsp.HLE.Modules;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.TPointer32;
import jpcsp.HLE.kernel.Managers;
import jpcsp.HLE.kernel.types.IAction;
import jpcsp.HLE.kernel.types.SceKernelErrors;
import jpcsp.HLE.kernel.types.interrupts.AbstractAllegrexInterruptHandler;
import jpcsp.HLE.kernel.types.interrupts.AbstractInterruptHandler;
import jpcsp.HLE.kernel.types.interrupts.AfterSubIntrAction;
import jpcsp.HLE.kernel.types.interrupts.InterruptHandler;
import jpcsp.HLE.kernel.types.interrupts.InterruptState;
import jpcsp.HLE.kernel.types.interrupts.IntrHandler;
import jpcsp.HLE.kernel.types.interrupts.SubIntrHandler;
import jpcsp.HLE.kernel.types.interrupts.VBlankInterruptHandler;
import jpcsp.scheduler.Scheduler;

import org.apache.log4j.Logger;

public class IntrManager {
    protected static Logger log = Modules.getLogger("ThreadManForUser");

    // Exceptions
	public static final int EXCEP_INT = 0; // Interrupt
	public static final int EXCEP_ADEL = 4; // Load of instruction fetch exception
	public static final int EXCEP_ADES = 5; // Address store exception
	public static final int EXCEP_IBE = 6; // Instruction fetch bus error
	public static final int EXCEP_DBE = 7; // Load/store bus error
	public static final int EXCEP_SYS = 8; // Syscall
	public static final int EXCEP_BP = 9; // Breakpoint
	public static final int EXCEP_RI = 10; // Reserved instruction
	public static final int EXCEP_CPU = 11; // Coprocessor unusable
	public static final int EXCEP_OV = 12; // Arithmetic overflow
	public static final int EXCEP_FPE = 15; // Floating-point exception
	public static final int EXCEP_WATCH = 23; // Watch (reference to WatchHi/WatchLo)
	public static final int EXCEP_VCED = 31; // "Virtual Coherency Exception Data" (used for NMI handling apparently)

	public static final int IP0 = (1 << 0);
	public static final int IP1 = (1 << 1);
	public static final int IP2 = (1 << 2);
	public static final int IP3 = (1 << 3);
	public static final int IP4 = (1 << 4);
	public static final int IP5 = (1 << 5);
	public static final int IP6 = (1 << 6);
	public static final int IP7 = (1 << 7);

	// Interrupts
	public static final int PSP_GPIO_INTR                        =  4;
	public static final int PSP_ATA_INTR                         =  5;
	public static final int PSP_UMD_INTR                         =  6;
	public static final int PSP_MSCM0_INTR                       =  7;
	public static final int PSP_WLAN_INTR                        =  8;
	public static final int PSP_AUDIO_INTR                       = 10;
	public static final int PSP_I2C_INTR                         = 12;
	public static final int PSP_SIRS_INTR                        = 14;
	public static final int PSP_SYSTIMER0_INTR                   = 15;
	public static final int PSP_SYSTIMER1_INTR                   = 16;
	public static final int PSP_SYSTIMER2_INTR                   = 17;
	public static final int PSP_SYSTIMER3_INTR                   = 18;
	public static final int PSP_THREAD0_INTR                     = 19;
	public static final int PSP_NAND_INTR                        = 20;
	public static final int PSP_DMACPLUS_INTR                    = 21;
	public static final int PSP_DMA0_INTR                        = 22;
	public static final int PSP_DMA1_INTR                        = 23;
	public static final int PSP_MEMLMD_INTR                      = 24;
	public static final int PSP_GE_INTR                          = 25;
	public static final int PSP_USB_INTERRUPT_CONNECTION         = 26;
	public static final int PSP_EFLASH_ATA2_INTR                 = 27;
	public static final int PSP_EFLASH_DMA_INTR                  = 28;
	public static final int PSP_VBLANK_INTR                      = 30;
	public static final int PSP_MECODEC_INTR                     = 31;
	public static final int PSP_HPREMOTE_INTR                    = 36;
	public static final int PSP_USB_INTERRUPT4                   = 56;
	public static final int PSP_USB_INTERRUPT3                   = 57;
	public static final int PSP_USB_INTERRUPT_CABLE_CONNECTED    = 58;
	public static final int PSP_USB_INTERRUPT_CABLE_DISCONNECTED = 59;
	public static final int PSP_MSCM1_INTR                       = 60;
	public static final int PSP_MSCM2_INTR                       = 61;
	public static final int PSP_THREAD1_INTR                     = 65;
	public static final int PSP_INTERRUPT_INTR                   = 66;
	public static final int PSP_NUMBER_INTERRUPTS                = 67;
	private static String[] PSP_INTERRUPT_NAMES;

	public static final int VBLANK_SCHEDULE_MICROS = (1000000 + 30) / 60; // 1/60 second (rounded)

	protected static IntrManager instance = null;
	private Vector<LinkedList<AbstractInterruptHandler>> interrupts;
	protected IntrHandler[] intrHandlers;
	protected boolean insideInterrupt;
	protected List<AbstractAllegrexInterruptHandler> allegrexInterruptHandlers;
	// Deferred interrupts are interrupts that were triggered by the scheduler
	// while the interrupts were disabled.
	// They have to be processed as soon as the interrupts are re-enabled.
	protected List<AbstractInterruptHandler> deferredInterrupts;

	private VBlankInterruptHandler vblankInterruptHandler;

	public static IntrManager getInstance() {
		if (instance == null) {
			instance = new IntrManager();
		}

		return instance;
	}

	private IntrManager() {
		vblankInterruptHandler = new VBlankInterruptHandler();
	}

	public void reset() {
		stop();
		installDefaultInterrupts();
	}

	public void stop() {
		interrupts = new Vector<LinkedList<AbstractInterruptHandler>>(PSP_NUMBER_INTERRUPTS);
		interrupts.setSize(PSP_NUMBER_INTERRUPTS);
		intrHandlers = new IntrHandler[IntrManager.PSP_NUMBER_INTERRUPTS];
		allegrexInterruptHandlers = new LinkedList<AbstractAllegrexInterruptHandler>();

		deferredInterrupts = new LinkedList<AbstractInterruptHandler>();
	}

	public static String getInterruptName(int interruptNumber) {
		if (PSP_INTERRUPT_NAMES == null) {
			PSP_INTERRUPT_NAMES = new String[PSP_NUMBER_INTERRUPTS];
			PSP_INTERRUPT_NAMES[PSP_GPIO_INTR]      = "GPIO";
			PSP_INTERRUPT_NAMES[PSP_ATA_INTR]       = "ATA";
			PSP_INTERRUPT_NAMES[PSP_UMD_INTR]       = "UMD";
			PSP_INTERRUPT_NAMES[PSP_MSCM0_INTR]     = "MSCM0";
			PSP_INTERRUPT_NAMES[PSP_WLAN_INTR]      = "WLAN";
			PSP_INTERRUPT_NAMES[PSP_AUDIO_INTR]     = "AUDIO";
			PSP_INTERRUPT_NAMES[PSP_I2C_INTR]       = "I2C";
			PSP_INTERRUPT_NAMES[PSP_SIRS_INTR]      = "SIRS";
			PSP_INTERRUPT_NAMES[PSP_SYSTIMER0_INTR] = "SYSTIMER0";
			PSP_INTERRUPT_NAMES[PSP_SYSTIMER1_INTR] = "SYSTIMER1";
			PSP_INTERRUPT_NAMES[PSP_SYSTIMER2_INTR] = "SYSTIMER2";
			PSP_INTERRUPT_NAMES[PSP_SYSTIMER3_INTR] = "SYSTIMER3";
			PSP_INTERRUPT_NAMES[PSP_THREAD0_INTR]   = "THREAD0";
			PSP_INTERRUPT_NAMES[PSP_NAND_INTR]      = "NAND";
			PSP_INTERRUPT_NAMES[PSP_DMACPLUS_INTR]  = "DMACPLUS";
			PSP_INTERRUPT_NAMES[PSP_DMA0_INTR]      = "DMA0";
			PSP_INTERRUPT_NAMES[PSP_DMA1_INTR]      = "DMA1";
			PSP_INTERRUPT_NAMES[PSP_MEMLMD_INTR]    = "MEMLMD";
			PSP_INTERRUPT_NAMES[PSP_GE_INTR]        = "GE";
			PSP_INTERRUPT_NAMES[PSP_USB_INTERRUPT_CONNECTION] = "USB_CONNECTION";
			PSP_INTERRUPT_NAMES[PSP_EFLASH_ATA2_INTR] = "EFLASH_ATA2";
			PSP_INTERRUPT_NAMES[PSP_EFLASH_DMA_INTR]= "EFLASH_DMA";
			PSP_INTERRUPT_NAMES[PSP_VBLANK_INTR]    = "VBLANK";
			PSP_INTERRUPT_NAMES[PSP_MECODEC_INTR]   = "MECODEC";
			PSP_INTERRUPT_NAMES[PSP_HPREMOTE_INTR]  = "HPREMOTE";
			PSP_INTERRUPT_NAMES[PSP_MSCM1_INTR]     = "MSCM1";
			PSP_INTERRUPT_NAMES[PSP_MSCM2_INTR]     = "MSCM2";
			PSP_INTERRUPT_NAMES[PSP_THREAD1_INTR]   = "THREAD1";
			PSP_INTERRUPT_NAMES[PSP_INTERRUPT_INTR] = "INTERRUPT";
		}

		String name = null;
		if (interruptNumber >= 0 && interruptNumber < PSP_INTERRUPT_NAMES.length) {
			name = PSP_INTERRUPT_NAMES[interruptNumber];
		}

		if (name == null) {
			name = String.format("INTERRUPT_%X", interruptNumber);
		}

		return name;
	}

	private void installDefaultInterrupts() {
		// No default interrupts when running LLE
		if (RuntimeContextLLE.isLLEActive()) {
			return;
		}

		Scheduler scheduler = Emulator.getScheduler();

		// install VBLANK interrupt every 1/60 second
		scheduler.addAction(Scheduler.getNow() + VBLANK_SCHEDULE_MICROS, vblankInterruptHandler);
	}

	public void addDeferredInterrupt(AbstractInterruptHandler interruptHandler) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("addDeferredInterrupt insideInterrupt=%b, interruptsEnabled=%b", isInsideInterrupt(), Emulator.getProcessor().isInterruptsEnabled()));
		}
		deferredInterrupts.add(interruptHandler);
	}

	public boolean canExecuteInterruptNow() {
		return !isInsideInterrupt() && Emulator.getProcessor().isInterruptsEnabled();
	}

	public void onInterruptsEnabled() {
		if (!deferredInterrupts.isEmpty() && canExecuteInterruptNow()) {
			if (log.isDebugEnabled()) {
				log.debug("Executing deferred interrupts");
			}

			List<AbstractInterruptHandler> copyDeferredInterrupts = new LinkedList<AbstractInterruptHandler>(deferredInterrupts);
			deferredInterrupts.clear();
			executeInterrupts(copyDeferredInterrupts, null, null);
		}
	}

	public LinkedList<AbstractInterruptHandler> getInterruptHandlers(int intrNumber) {
		if (intrNumber < 0 || intrNumber >= PSP_NUMBER_INTERRUPTS) {
			return null;
		}

		return interrupts.get(intrNumber);
	}

	public void addInterruptHandler(int interruptNumber, AbstractInterruptHandler interruptHandler) {
		if (interruptNumber < 0 || interruptNumber >= PSP_NUMBER_INTERRUPTS) {
			return;
		}

		LinkedList<AbstractInterruptHandler> interruptHandlers = interrupts.get(interruptNumber);
		if (interruptHandlers == null) {
			interruptHandlers = new LinkedList<AbstractInterruptHandler>();
			interrupts.set(interruptNumber, interruptHandlers);
		}

		interruptHandlers.add(interruptHandler);
	}

	public boolean removeInterruptHandler(int intrNumber, AbstractInterruptHandler interruptHandler) {
		if (intrNumber < 0 || intrNumber >= PSP_NUMBER_INTERRUPTS) {
			return false;
		}

		LinkedList<AbstractInterruptHandler> interruptHandlers = interrupts.get(intrNumber);
		if (interruptHandlers == null) {
			return false;
		}

		return interruptHandlers.remove(interruptHandler);
	}


	protected void onEndOfInterrupt() {
		if (log.isDebugEnabled()) {
			log.debug("End of Interrupt");
		}

		allegrexInterruptHandlers.clear();

		// Schedule to a thread having a higher priority if one is ready to run
		Modules.ThreadManForUserModule.hleRescheduleCurrentThread();
		onInterruptsEnabled();
	}

	public void pushAllegrexInterruptHandler(AbstractAllegrexInterruptHandler allegrexInterruptHandler) {
		allegrexInterruptHandlers.add(allegrexInterruptHandler);
	}

	public void continueCallAllegrexInterruptHandler(InterruptState interruptState, Iterator<AbstractAllegrexInterruptHandler> allegrexInterruptHandlersIterator, IAction continueAction) {
		boolean somethingExecuted = false;
		do {
			if (allegrexInterruptHandlersIterator != null && allegrexInterruptHandlersIterator.hasNext()) {
				AbstractAllegrexInterruptHandler allegrexInterruptHandler = allegrexInterruptHandlersIterator.next();
				if (allegrexInterruptHandler != null) {
					if (log.isDebugEnabled()) {
						log.debug("Calling InterruptHandler " + allegrexInterruptHandler.toString());
					}
					allegrexInterruptHandler.copyArgumentsToCpu(Emulator.getProcessor().cpu);
					Modules.ThreadManForUserModule.callAddress(allegrexInterruptHandler.getAddress(), continueAction, true, allegrexInterruptHandler.getGp());
					somethingExecuted = true;
				}
			} else {
				break;
			}
		} while (!somethingExecuted);

		if (!somethingExecuted) {
			// No more handlers, end of interrupt
			setInsideInterrupt(interruptState.restore(Emulator.getProcessor().cpu));
			IAction afterInterruptAction = interruptState.getAfterInterruptAction();
			if (afterInterruptAction != null) {
				afterInterruptAction.execute();
			}
			onEndOfInterrupt();
		}
	}

	protected void executeInterrupts(List<AbstractInterruptHandler> interruptHandlers, IAction afterInterruptAction, IAction afterHandlerAction) {
		if (interruptHandlers != null) {
			for (Iterator<AbstractInterruptHandler> it = interruptHandlers.iterator(); it.hasNext(); ) {
				AbstractInterruptHandler interruptHandler = it.next();
				if (interruptHandler != null) {
					interruptHandler.execute();
				}
			}
		}

		if (allegrexInterruptHandlers.isEmpty()) {
			if (afterInterruptAction != null) {
				afterInterruptAction.execute();
			}
			onEndOfInterrupt();
		} else {
			InterruptState interruptState = new InterruptState();
			interruptState.save(insideInterrupt, Emulator.getProcessor().cpu, afterInterruptAction, afterHandlerAction);
			setInsideInterrupt(true);

			Iterator<AbstractAllegrexInterruptHandler> allegrexInterruptHandlersIterator = allegrexInterruptHandlers.iterator();
			IAction continueAction = new AfterSubIntrAction(this, interruptState, allegrexInterruptHandlersIterator);

			continueCallAllegrexInterruptHandler(interruptState, allegrexInterruptHandlersIterator, continueAction);
		}
	}

	public void triggerInterrupt(int interruptNumber) {
		triggerInterrupt(interruptNumber, null, null);
	}

	public void triggerInterrupt(int interruptNumber, IAction afterInterruptAction, IAction afterHandlerAction) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Triggering Interrupt %s(0x%X)", getInterruptName(interruptNumber), interruptNumber));
		}

		executeInterrupts(getInterruptHandlers(interruptNumber), afterInterruptAction, afterHandlerAction);
	}

	public void triggerInterrupt(int interruptNumber, IAction afterInterruptAction, IAction afterHandlerAction, AbstractAllegrexInterruptHandler allegrexInterruptHandler) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Triggering Interrupt %s(0x%X) at 0x%08X", getInterruptName(interruptNumber), interruptNumber, allegrexInterruptHandler.getAddress()));
		}

		// Trigger only this interrupt handler
		allegrexInterruptHandlers.add(allegrexInterruptHandler);
		executeInterrupts(null, afterInterruptAction, afterHandlerAction);
	}

	public boolean isInsideInterrupt() {
		return insideInterrupt;
	}

	public void setInsideInterrupt(boolean insideInterrupt) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("setInsideInterrupt insideInterrupt=%b", insideInterrupt));
		}
		this.insideInterrupt = insideInterrupt;
	}

	public void addVBlankAction(IAction action) {
		vblankInterruptHandler.addVBlankAction(action);
	}

	public boolean removeVBlankAction(IAction action) {
		return vblankInterruptHandler.removeVBlankAction(action);
	}

	public void addVBlankActionOnce(IAction action) {
		vblankInterruptHandler.addVBlankActionOnce(action);
	}

	public boolean removeVBlankActionOnce(IAction action) {
		return vblankInterruptHandler.removeVBlankActionOnce(action);
	}

	public int sceKernelRegisterSubIntrHandler(int intrNumber, int subIntrNumber, TPointer handlerAddress, int handlerArgument) {
		if (intrNumber < 0 || intrNumber >= IntrManager.PSP_NUMBER_INTERRUPTS || subIntrNumber < 0) {
			return SceKernelErrors.ERROR_KERNEL_INVALID_INTR_NUMBER;
		}

		if (intrHandlers[intrNumber] == null) {
			IntrHandler intrHandler = new IntrHandler();
			intrHandlers[intrNumber] = intrHandler;
			addInterruptHandler(intrNumber, intrHandler);
		} else if (intrHandlers[intrNumber].getSubIntrHandler(subIntrNumber) != null) {
			return SceKernelErrors.ERROR_KERNEL_SUBINTR_ALREADY_REGISTERED;
		}

		int gp = Managers.modules.getModuleGpByAddress(handlerAddress.getAddress());
		SubIntrHandler subIntrHandler = new SubIntrHandler(handlerAddress.getAddress(), gp, subIntrNumber, handlerArgument);
		subIntrHandler.setEnabled(false);
		intrHandlers[intrNumber].addSubIntrHandler(subIntrNumber, subIntrHandler);

		return 0;
	}

	public int sceKernelReleaseSubIntrHandler(int intrNumber, int subIntrNumber) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("sceKernelReleaseSubIntrHandler(%d, %d)", intrNumber, subIntrNumber));
		}

		if (intrNumber < 0 || intrNumber >= IntrManager.PSP_NUMBER_INTERRUPTS || subIntrNumber < 0) {
			return SceKernelErrors.ERROR_KERNEL_INVALID_INTR_NUMBER;
		}

		if (intrHandlers[intrNumber] == null) {
			return SceKernelErrors.ERROR_KERNEL_SUBINTR_NOT_REGISTERED;
		}

		if (!intrHandlers[intrNumber].removeSubIntrHandler(subIntrNumber)) {
			return SceKernelErrors.ERROR_KERNEL_SUBINTR_NOT_REGISTERED;
		}

		return 0;
	}

	protected int hleKernelEnableDisableSubIntr(int intrNumber, int subIntrNumber, boolean enabled) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("sceKernel%sSubIntr(%d, %d)", enabled ? "Enable" : "Disable", intrNumber, subIntrNumber));
		}

		if (intrNumber < 0 || intrNumber >= IntrManager.PSP_NUMBER_INTERRUPTS) {
			return -1;
		}

		if (intrHandlers[intrNumber] == null) {
			return -1;
		}

		SubIntrHandler subIntrHandler = intrHandlers[intrNumber].getSubIntrHandler(subIntrNumber);
		if (subIntrHandler == null) {
			return -1;
		}

		subIntrHandler.setEnabled(enabled);

		return 0;
	}

	public int sceKernelEnableSubIntr(int intrNumber, int subIntrNumber) {
		return hleKernelEnableDisableSubIntr(intrNumber, subIntrNumber, true);
	}

	public int sceKernelDisableSubIntr(int intrNumber, int subIntrNumber) {
		return hleKernelEnableDisableSubIntr(intrNumber, subIntrNumber, false);
	}

	public int sceKernelRegisterIntrHandler(int intrNumber, int unknown, TPointer func, int funcArg, TPointer32 handler) {
		if (intrNumber < 0 || intrNumber >= IntrManager.PSP_NUMBER_INTERRUPTS) {
			return -1;
		}

		int size = 0;
		int attr = 0;
		int cb = 0;
		if (handler.isNotNull()) {
			size = handler.getValue(0);
			attr = handler.getValue(4);
			cb = handler.getValue(8);
		}
		int gp = Managers.modules.getModuleGpByAddress(func.getAddress());

		if (log.isDebugEnabled()) {
			log.debug(String.format("sceKernelRegisterIntrHandler intrNumber=0x%X, func=%s, gp=0x%08X, funcArg=0x%08X, size=0x%X, attr=0x%X, cb=0x%08X", intrNumber, func, gp, funcArg, size, attr, cb));
		}

		if (intrHandlers[intrNumber] == null) {
			IntrHandler intrHandler = new IntrHandler();
			intrHandlers[intrNumber] = intrHandler;
		}

		AbstractInterruptHandler interruptHandler = new InterruptHandler(func, gp, intrNumber, funcArg, cb);
		addInterruptHandler(intrNumber, interruptHandler);

		return 0;
	}

	public int sceKernelCallSubIntrHandler(int intrNumber, int subIntrNumber, int handlerArg0, int handlerArg2) {
		if (intrNumber < 0 || intrNumber >= IntrManager.PSP_NUMBER_INTERRUPTS) {
			return -1;
		}

		SubIntrHandler subIntrHandler = intrHandlers[intrNumber].getSubIntrHandler(subIntrNumber);
		if (subIntrHandler == null) {
			return -1;
		}

		if (isInsideInterrupt()) {
			Modules.ThreadManForUserModule.executeCallback(subIntrHandler.getAddress(), subIntrHandler.getGp(), null, subIntrHandler.getId(), subIntrHandler.getArgument());
		} else {
			triggerInterrupt(intrNumber, null, null, subIntrHandler);
		}

		return 0;
	}
}