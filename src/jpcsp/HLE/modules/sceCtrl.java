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

import jpcsp.HLE.BufferInfo;
import jpcsp.HLE.BufferInfo.LengthInfo;
import jpcsp.HLE.BufferInfo.Usage;
import jpcsp.HLE.CanBeNull;
import jpcsp.HLE.CheckArgument;
import jpcsp.HLE.HLEFunction;
import jpcsp.HLE.HLEModule;
import jpcsp.HLE.HLEUnimplemented;
import jpcsp.HLE.SceKernelErrorException;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.TPointer32;

import java.util.LinkedList;
import java.util.List;

import jpcsp.Controller;
import jpcsp.Memory;
import jpcsp.State;
import jpcsp.HLE.Modules;
import jpcsp.HLE.kernel.Managers;
import jpcsp.HLE.kernel.managers.SystemTimeManager;
import jpcsp.HLE.kernel.types.IAction;
import jpcsp.HLE.kernel.types.SceKernelErrors;
import jpcsp.HLE.kernel.types.SceKernelThreadInfo;

import org.apache.log4j.Logger;

public class sceCtrl extends HLEModule {
    public static Logger log = Modules.getLogger("sceCtrl");

    private int cycle;
    private int mode;
    private int uiMake;
    private int uiBreak;
    private int uiPress;
    private int uiRelease;
    private int TimeStamp; // microseconds
    private byte Lx;
    private byte Ly;
    private byte Rx;
    private byte Ry;
    private int Buttons;

    // IdleCancelThreshold
    private int idlereset;
    private int idleback;
    public final static int PSP_CTRL_SELECT   = 0x0000001;
    public final static int PSP_CTRL_START    = 0x0000008;
    public final static int PSP_CTRL_UP       = 0x0000010;
    public final static int PSP_CTRL_RIGHT    = 0x0000020;
    public final static int PSP_CTRL_DOWN     = 0x0000040;
    public final static int PSP_CTRL_LEFT     = 0x0000080;
    public final static int PSP_CTRL_LTRIGGER = 0x0000100;
    public final static int PSP_CTRL_RTRIGGER = 0x0000200;
    public final static int PSP_CTRL_TRIANGLE = 0x0001000;
    public final static int PSP_CTRL_CIRCLE   = 0x0002000;
    public final static int PSP_CTRL_CROSS    = 0x0004000;
    public final static int PSP_CTRL_SQUARE   = 0x0008000;
    public final static int PSP_CTRL_HOME     = 0x0010000;
    public final static int PSP_CTRL_HOLD     = 0x0020000;
    public final static int PSP_CTRL_WLAN_UP  = 0x0040000;
    public final static int PSP_CTRL_REMOTE   = 0x0080000;
    public final static int PSP_CTRL_VOLUP    = 0x0100000;
    public final static int PSP_CTRL_VOLDOWN  = 0x0200000;
    public final static int PSP_CTRL_SCREEN   = 0x0400000;
    public final static int PSP_CTRL_NOTE     = 0x0800000;
    public final static int PSP_CTRL_DISC     = 0x1000000;
    public final static int PSP_CTRL_MS       = 0x2000000;
    public final static int PSP_CTRL_SLIDE_OPEN = 0x20000000;

    // PspCtrlMode
    public final static int PSP_CTRL_MODE_DIGITAL = 0x0;
    public final static int PSP_CTRL_MODE_ANALOG = 0x1;
    public final static int PSP_CTRL_MODE_ANALOG_DIGITAL = 0x1;
    public final static int PSP_CTRL_MODE_CABLE_TYPE_REQUEST = 0x2;
    protected IAction sampleAction = null;
    protected Sample samples[];
    protected int currentSamplingIndex;
    protected int currentReadingIndex;
    protected int latchSamplingCount;
    // Only those buttons can be read in user mode
    private final static int PSP_CTRL_USER_MODE_BUTTONS = PSP_CTRL_SELECT | PSP_CTRL_START | PSP_CTRL_UP | PSP_CTRL_RIGHT | PSP_CTRL_DOWN | PSP_CTRL_LEFT | PSP_CTRL_LTRIGGER | PSP_CTRL_RTRIGGER | PSP_CTRL_TRIANGLE | PSP_CTRL_CIRCLE | PSP_CTRL_CROSS | PSP_CTRL_SQUARE | PSP_CTRL_HOME | PSP_CTRL_HOLD;
    // PSP remembers the last 64 samples.
    protected final static int SAMPLE_BUFFER_SIZE = 64;
    protected List<ThreadWaitingForSampling> threadsWaitingForSampling;

    public boolean isModeDigital() {
    	return (mode & PSP_CTRL_MODE_ANALOG_DIGITAL) == PSP_CTRL_MODE_DIGITAL;
    }

    public void setSamplingMode(int mode) {
    	this.mode = mode;
    }

    private static int getTimestamp() {
    	return ((int) SystemTimeManager.getSystemTime()) & 0x7FFFFFFF;
    }

    private void setButtons(byte Lx, byte Ly, byte Rx, byte Ry, int Buttons, boolean hasRightAnalogController) {
        int oldButtons = this.Buttons;

        this.TimeStamp = getTimestamp();
        this.Lx = Lx;
        this.Ly = Ly;
        if (hasRightAnalogController) {
        	this.Rx = Rx;
        	this.Ry = Ry;
        } else {
        	this.Rx = 0;
        	this.Ry = 0;
        }
        this.Buttons = Buttons;

        if (isModeDigital()) {
            // PSP_CTRL_MODE_DIGITAL
            // moving the analog stick has no effect and always returns 128,128
            this.Lx = Controller.analogCenter;
            this.Ly = Controller.analogCenter;
            if (hasRightAnalogController) {
            	this.Rx = Controller.analogCenter;
            	this.Ry = Controller.analogCenter;
            }
        }

        int changed = oldButtons ^ Buttons;
        int unpressed = ~Buttons;

        uiMake |= Buttons & changed;
        uiBreak |= unpressed & changed;
        uiPress |= Buttons;
        uiRelease |= unpressed;
    }

    @Override
    public void start() {
        uiMake = 0;
        uiBreak = 0;
        uiPress = 0;
        uiRelease = ~uiPress;

        Lx = Controller.analogCenter;
        Ly = Controller.analogCenter;
        Rx = Controller.analogCenter;
        Ry = Controller.analogCenter;
        Buttons = 0;

        idlereset = -1;
        idleback = -1;

        mode = PSP_CTRL_MODE_DIGITAL; // check initial mode
        cycle = 0;

        // Allocate 1 more entry because we always leave 1 entry free
        // for the internal management
        // (to differentiate a full buffer from an empty one).
        samples = new Sample[SAMPLE_BUFFER_SIZE + 1];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new Sample();
            samples[i].setValues(0, Lx, Ly, Rx, Ry, Buttons);
        }
        currentSamplingIndex = 0;
        currentReadingIndex = 0;
        latchSamplingCount = 0;

        threadsWaitingForSampling = new LinkedList<ThreadWaitingForSampling>();

        if (sampleAction == null) {
            sampleAction = new SamplingAction();
            Managers.intr.addVBlankAction(sampleAction);
        }

        super.start();
    }

    protected class SamplingAction implements IAction {
        @Override
        public void execute() {
            hleCtrlExecuteSampling();
        }
    }

    protected static class ThreadWaitingForSampling {
        SceKernelThreadInfo thread;
        int readAddr;
        int readCount;
        boolean readPositive;

        public ThreadWaitingForSampling(SceKernelThreadInfo thread, int readAddr, int readCount, boolean readPositive) {
            this.thread = thread;
            this.readAddr = readAddr;
            this.readCount = readCount;
            this.readPositive = readPositive;
        }
    }

    protected static class Sample {
        private int TimeStamp; // microseconds
        private byte Lx;
        private byte Ly;
        private byte Rx;
        private byte Ry;
        private int Buttons;

        public void setValues(int TimeStamp, byte Lx, byte Ly, byte Rx, byte Ry, int Buttons) {
			this.TimeStamp = TimeStamp;
			this.Lx = Lx;
			this.Ly = Ly;
			this.Rx = Rx;
			this.Ry = Ry;
			this.Buttons = Buttons;
		}

        public int write(Memory mem, int addr, boolean positive, boolean userMode) {
        	int buttons = Buttons;
        	if (userMode) {
        		// Only those buttons can be read in user mode
        		buttons &= PSP_CTRL_USER_MODE_BUTTONS;
        	}
        	if (!positive) {
        		buttons = ~buttons;
        	}

            mem.write32(addr, TimeStamp);
            mem.write32(addr + 4, buttons);
            mem.write8(addr + 8, Lx);
            mem.write8(addr + 9, Ly);

            // These 2 values are always set to 0 on a PSP,
            // but are used for a second analog stick on the PS3 PSP emulator (for HD remaster)
            mem.write8(addr + 10, Rx);
            mem.write8(addr + 11, Ry);

            // Always set to 0
            mem.write8(addr + 12, (byte) 0);
            mem.write8(addr + 13, (byte) 0);
            mem.write8(addr + 14, (byte) 0);
            mem.write8(addr + 15, (byte) 0);

            return addr + 16;
        }

        @Override
        public String toString() {
            return String.format("TimeStamp=%d,Lx=%d,Ly=%d,Rx=%d,Ry=%d,Buttons=%07X", TimeStamp, Lx, Ly, Rx, Ry, Buttons);
        }
    }

    /**
     * Increment (or decrement) the given Sample Index
     * (currentSamplingIndex or currentReadingIndex).
     *
     * @param index the current index value
     *              0 <= index < samples.length
     * @param count the increment (or decrement) value.
     *              -samples.length <= count <= samples.length
     * @return      the incremented index value
     *              0 <= returned value < samples.length
     */
    protected int incrementSampleIndex(int index, int count) {
        index += count;
        if (index >= samples.length) {
            index -= samples.length;
        } else if (index < 0) {
            index += samples.length;
        }

        return index;
    }

    /**
     * Increment the given Sample Index by 1
     * (currentSamplingIndex or currentReadingIndex).
     *
     * @param index the current index value
     *              0 <= index < samples.length
     * @return      the index value incremented by 1
     *              0 <= returned value < samples.length
     */
    protected int incrementSampleIndex(int index) {
        return incrementSampleIndex(index, 1);
    }

    protected int getNumberOfAvailableSamples() {
        int n = currentSamplingIndex - currentReadingIndex;
        if (n < 0) {
            n += samples.length;
        }

        return n;
    }

    public int checkThreshold(int threshold) {
    	if (threshold < -1 || threshold > 128) {
    		throw new SceKernelErrorException(SceKernelErrors.ERROR_INVALID_VALUE);
    	}

    	return threshold;
    }

    public int checkMode(int mode) {
    	if (mode != PSP_CTRL_MODE_DIGITAL && mode != PSP_CTRL_MODE_ANALOG) {
    		throw new SceKernelErrorException(SceKernelErrors.ERROR_INVALID_MODE);
    	}

    	return mode;
    }

    public int checkCycle(int cycle) {
    	if (cycle < 0 || (cycle > 0 && cycle < 5555) || cycle > 20000) {
    		throw new SceKernelErrorException(SceKernelErrors.ERROR_INVALID_VALUE);
    	}
    	return cycle;
    }

    public void hleCtrlExecuteSampling() {
    	if (log.isTraceEnabled()) {
    		log.trace("hleCtrlExecuteSampling");
    	}

        Controller controller = State.controller;
        controller.hleControllerPoll();

        setButtons(controller.getLx(), controller.getLy(), controller.getRx(), controller.getRy(), controller.getButtons(), controller.hasRightAnalogController());

        latchSamplingCount++;

        Sample currentSampling = samples[currentSamplingIndex];
        currentSampling.setValues(TimeStamp, Lx, Ly, Rx, Ry, Buttons);

        currentSamplingIndex = incrementSampleIndex(currentSamplingIndex);
        if (currentSamplingIndex == currentReadingIndex) {
            currentReadingIndex = incrementSampleIndex(currentReadingIndex);
        }

        while (!threadsWaitingForSampling.isEmpty()) {
            ThreadWaitingForSampling wait = threadsWaitingForSampling.remove(0);
            if (wait.thread.isWaitingForType(SceKernelThreadInfo.JPCSP_WAIT_CTRL)) {
	            if (log.isDebugEnabled()) {
	                log.debug("hleExecuteSampling waiting up thread " + wait.thread);
	            }
	            wait.thread.cpuContext._v0 = hleCtrlReadBufferImmediately(wait.readAddr, wait.readCount, wait.readPositive, false);
	            Modules.ThreadManForUserModule.hleUnblockThread(wait.thread.uid);
	            break;
            }

            if (log.isDebugEnabled()) {
                log.debug("hleExecuteSampling thread " + wait.thread + " was no longer blocked");
            }
        }
    }

    protected int hleCtrlReadBufferImmediately(int addr, int count, boolean positive, boolean peek) {
    	if (count < 0 || count > SAMPLE_BUFFER_SIZE) {
    		return SceKernelErrors.ERROR_INVALID_SIZE;
    	}

        Memory mem = Memory.getInstance();

        // If more samples are available than requested, read the more recent ones
        int available = getNumberOfAvailableSamples();
        int readIndex;
        if (available > count || peek) {
            readIndex = incrementSampleIndex(currentSamplingIndex, -count);
        } else {
            count = available;
            readIndex = currentReadingIndex;
        }

        if (!peek) {
            // Forget the remaining samples if they are not read now
            currentReadingIndex = currentSamplingIndex;
        }

        boolean userMode = false;
        SceKernelThreadInfo currentThread = Modules.ThreadManForUserModule.getCurrentThread();
        if (currentThread != null) {
        	userMode = currentThread.isUserMode();
        }

        for (int ctrlCount = 0; ctrlCount < count; ctrlCount++) {
        	if (log.isTraceEnabled()) {
        		log.trace(String.format("now=%d, samples[%d]=%s", getTimestamp(), readIndex, samples[readIndex]));
        	}
            addr = samples[readIndex].write(mem, addr, positive, userMode);
            readIndex = incrementSampleIndex(readIndex);
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("hleCtrlReadBufferImmediately(positive=%b, peek=%b) returning %d", positive, peek, count));
        }

        return count;
    }

    protected int hleCtrlReadBuffer(int addr, int count, boolean positive) {
    	if (count < 0 || count > SAMPLE_BUFFER_SIZE) {
    		return SceKernelErrors.ERROR_INVALID_SIZE;
    	}

    	// Some data available in sample buffer?
        if (getNumberOfAvailableSamples() > 0) {
            // Yes, read immediately
            return hleCtrlReadBufferImmediately(addr, count, positive, false);
        }

        // No, wait for next sampling
        ThreadManForUser threadMan = Modules.ThreadManForUserModule;
        SceKernelThreadInfo currentThread = threadMan.getCurrentThread();
        ThreadWaitingForSampling threadWaitingForSampling = new ThreadWaitingForSampling(currentThread, addr, count, positive);
        threadsWaitingForSampling.add(threadWaitingForSampling);
        threadMan.hleBlockCurrentThread(SceKernelThreadInfo.JPCSP_WAIT_CTRL);

        if (log.isDebugEnabled()) {
            log.debug("hleCtrlReadBuffer waiting for sample");
        }

        return 0;
    }

    @HLEFunction(nid = 0x6A2774F3, version = 150, checkInsideInterrupt = true)
    public int sceCtrlSetSamplingCycle(@CheckArgument("checkCycle") int newCycle) {
    	int oldCycle = cycle;
        this.cycle = newCycle;

        if (log.isDebugEnabled()) {
            log.debug(String.format("sceCtrlSetSamplingCycle cycle=%d returning %d", newCycle, oldCycle));
        }

        return oldCycle;
    }

    @HLEFunction(nid = 0x02BAAD91, version = 150, checkInsideInterrupt = true)
    public int sceCtrlGetSamplingCycle(TPointer32 cycleAddr) {
    	cycleAddr.setValue(cycle);

        return 0;
    }

    @HLEFunction(nid = 0x1F4011E6, version = 150, checkInsideInterrupt = true)
    @HLEFunction(nid = 0xF6E94EA3, version = 150, checkInsideInterrupt = true)
    public int sceCtrlSetSamplingMode(@CheckArgument("checkMode") int newMode) {
        int oldMode = mode;
        setSamplingMode(newMode);

        if (log.isDebugEnabled()) {
            log.debug(String.format("sceCtrlSetSamplingMode mode=%d returning %d", newMode, oldMode));
        }

        return oldMode;
    }

    @HLEFunction(nid = 0xDA6B76A1, version = 150)
    @HLEFunction(nid = 0xF8EC18BD, version = 150)
    public int sceCtrlGetSamplingMode(TPointer32 modeAddr) {
    	modeAddr.setValue(mode);

    	return 0;
    }

    @HLEFunction(nid = 0x3A622550, version = 150)
    @HLEFunction(nid = 0xC4AAD55F, version = 371)
    @HLEFunction(nid = 0x2BA616AF, version = 660)
    public int sceCtrlPeekBufferPositive(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=16, usage=Usage.out) TPointer dataAddr, int numBuf) {
        return hleCtrlReadBufferImmediately(dataAddr.getAddress(), numBuf, true, true);
    }

    @HLEFunction(nid = 0xC152080A, version = 150)
    public int sceCtrlPeekBufferNegative(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=16, usage=Usage.out) TPointer dataAddr, int numBuf) {
        return hleCtrlReadBufferImmediately(dataAddr.getAddress(), numBuf, false, true);
    }

    @HLEFunction(nid = 0x1F803938, version = 150, checkInsideInterrupt = true)
    @HLEFunction(nid = 0x454455AC, version = 371)
    @HLEFunction(nid = 0xD073ECA4, version = 620)
    @HLEFunction(nid = 0x9F3038AC, version = 639)
    @HLEFunction(nid = 0xBE30CED0, version = 660)
    public int sceCtrlReadBufferPositive(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=16, usage=Usage.out) TPointer dataAddr, int numBuf) {
        return hleCtrlReadBuffer(dataAddr.getAddress(), numBuf, true);
    }

    @HLEFunction(nid = 0x60B81F86, version = 150, checkInsideInterrupt = true)
    public int sceCtrlReadBufferNegative(@BufferInfo(lengthInfo=LengthInfo.fixedLength, length=16, usage=Usage.out) TPointer dataAddr, int numBuf) {
        return hleCtrlReadBuffer(dataAddr.getAddress(), numBuf, false);
    }

    @HLEFunction(nid = 0xB1D0E5CD, version = 150)
    public int sceCtrlPeekLatch(TPointer32 latchAddr) {
    	latchAddr.setValue(0, uiMake);
        latchAddr.setValue(4, uiBreak);
        latchAddr.setValue(8, uiPress);
        latchAddr.setValue(12, uiRelease);

        return latchSamplingCount;
    }

    @HLEFunction(nid = 0x0B588501, version = 150)
    public int sceCtrlReadLatch(TPointer32 latchAddr) {
    	latchAddr.setValue(0, uiMake);
        latchAddr.setValue(4, uiBreak);
        latchAddr.setValue(8, uiPress);
        latchAddr.setValue(12, uiRelease);

        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceCtrlReadLatch uiMake=0x%06X, uiBreak=0x%06X, uiPress=0x%06X, uiRelease=0x%06X, returning %d", uiMake, uiBreak, uiPress, uiRelease, latchSamplingCount));
        }

        uiMake = 0;
        uiBreak = 0;
        uiPress = 0;
        uiRelease = 0;

        int prevLatchSamplingCount = latchSamplingCount;
        latchSamplingCount = 0;
        
        return prevLatchSamplingCount;
    }

    @HLEFunction(nid = 0xA7144800, version = 150)
    public int sceCtrlSetIdleCancelThreshold(@CheckArgument("checkThreshold") int idlereset, @CheckArgument("checkThreshold") int idleback) {
        this.idlereset = idlereset;
        this.idleback  = idleback;

        return 0;
    }

    @HLEFunction(nid = 0x687660FA, version = 150)
    public int sceCtrlGetIdleCancelThreshold(@CanBeNull TPointer32 idleresetAddr, @CanBeNull TPointer32 idlebackAddr) {
    	idleresetAddr.setValue(idlereset);
    	idlebackAddr.setValue(idleback);

        return 0;
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0x348D99D4, version = 150)
    public int sceCtrl_348D99D4() {
    	return 0;
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0xAF5960F3, version = 150)
    public int sceCtrl_AF5960F3() {
        return 0;
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0xA68FD260, version = 150)
    public int sceCtrlClearRapidFire() {
        return 0;
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0x6841BE1A, version = 150)
    public int sceCtrlSetRapidFire() {
        return 0;
    }

    @HLEUnimplemented
    @HLEFunction(nid = 0x6C86AF22, version = 660)
	public int sceCtrl_driver_6C86AF22(int unknown) {
    	return 0;
	}

    @HLEUnimplemented
    @HLEFunction(nid = 0x365BE224, version = 660)
	public int sceCtrlUpdateCableTypeReq() {
    	// Has no parameters
    	return 0;
	}
}