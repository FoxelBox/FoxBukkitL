package com.foxelbox.foxbukkit.lua;

import com.foxelbox.foxbukkit.core.FoxBukkit;
import org.bukkit.event.Listener;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;

public class LuaThread extends Thread implements Listener {
    private final Globals g;
    private volatile boolean running = true;

    private final LinkedBlockingQueue<Invoker> pendingTasks =  new LinkedBlockingQueue<>();

    private final ChatMessageManager chatMessageManager = new ChatMessageManager(this);
    private final EventManager eventManager = new EventManager(this);

    public EventManager getEventManager() {
        return eventManager;
    }

    public ChatMessageManager getChatMessageManager() {
        return chatMessageManager;
    }

    public static abstract class Invoker implements Runnable {
        private volatile LuaValue result = null;
        protected volatile boolean running = false;

        private final LuaThread luaThread;
        public Invoker(LuaThread luaThread) {
            this.luaThread = luaThread;
        }

        public final Invoker waitOnCompletion() {
            try {
                synchronized (this) {
                    while (this.running) {
                        this.wait();
                    }
                }
            } catch (InterruptedException e) { }
            return this;
        }

        public final Invoker reset() {
            synchronized (this) {
                if(running) {
                    waitOnCompletion();
                }
                result = null;
            }
            return this;
        }

        public final LuaValue getResult() {
            run(true);
            return result;
        }

        @Override
        public final void run() {
            run(true);
        }

        public final void run(boolean wait) {
            synchronized (this) {
                if (!running) {
                    running = true;
                    synchronized (luaThread) {
                        luaThread.pendingTasks.add(this);
                        luaThread.notify();
                    }
                }
                if (wait) {
                    waitOnCompletion();
                }
            }
        }

        protected final void start() {
            synchronized (this) {
                try {
                    synchronized (luaThread.g) {
                        result = invoke();
                    }
                } catch (Exception e) {
                    System.err.println("Exception running Invoker");
                    e.printStackTrace();
                    result = null;
                }
                running = false;
                this.notify();
            }
        }

        protected abstract LuaValue invoke();
    }

    public static class LuaFunctionInvoker extends Invoker {
        private final LuaFunction function;

        public LuaFunctionInvoker(LuaThread luaThread, LuaFunction function) {
            super(luaThread);
            this.function = function;
        }

        @Override
        protected LuaValue invoke() {
            return function.call();
        }
    }

    public void runOnMainThread(final LuaFunction function) {
        FoxBukkit.instance.getServer().getScheduler().scheduleSyncDelayedTask(FoxBukkit.instance, new LuaFunctionInvoker(LuaThread.this, function));
    }

    public LuaThread() {
        this(JsePlatform.debugGlobals());
    }

    public LuaThread(Globals g) {
        this.g = g;
    }

    @Override
    public void run() {
        try {
            synchronized (g) {
                g.set("__LUA_THREAD__", CoerceJavaToLua.coerce(this));
                g.set("__ROOTDIR__", FoxBukkit.instance.getLuaFolder().getAbsolutePath());
                g.loadfile(new File(FoxBukkit.instance.getLuaFolder(), "init.lua").getAbsolutePath()).call();
            }
            while(running) {
                Invoker invoker;
                while ((invoker = pendingTasks.poll()) != null) {
                    invoker.start();
                }
                synchronized (this) {
                    if(pendingTasks.isEmpty()) {
                        this.wait();
                    }
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public synchronized void terminate() {
        if(!running) {
            return;
        }
        running = false;

        synchronized (this) {
            synchronized (g) {
                running = false;
                pendingTasks.clear();
                eventManager.unregisterAll();
                this.notify();
            }
        }

        try {
            this.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //Ensure there are no leftover events. At this point the thread has ended so it is impossible for more to come up
        eventManager.unregisterAll();
        pendingTasks.clear();
    }
}
