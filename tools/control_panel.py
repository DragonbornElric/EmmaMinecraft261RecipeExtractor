"""
GOAP Control Panel — tkinter GUI for monitoring and controlling the Minecraft GOAP planner.

5 tabs:
  1. Control + Live Status   — mode buttons, personality sliders, quick commands
  2. GOAP Auction            — full score decomposition per action
  3. Goal Tree               — parent/derived goal hierarchy with satisfaction status
  4. Debug Log + History     — switch history, combat log, strategy knowledge, JSONL logging
  5. Commands                — all 45+ client commands organized by category with output panel

Usage:
    python tools/control_panel.py [--port 8765]

Requires the Minecraft bridge mod to be running on the specified WebSocket port.
"""

from __future__ import annotations

import json
import logging
import os
import sys
import threading
import time
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import messagebox, ttk

# Add project root to path so we can import gamer
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from gamer.emmatone_client import EmmatoneClient  # noqa: E402
from gamer.gamer_mode import GamerMode  # noqa: E402
from gamer.hero_mode import HeroMode  # noqa: E402

log = logging.getLogger(__name__)

POLL_INTERVAL_MS = 1000
WS_PORT = 8765


# ── Utility ───────────────────────────────────────────────────────

def format_score(v: float | None) -> str:
    if v is None or v == 0:
        return "—"
    return f"{v:.2f}"


def bar_text(current: float, maximum: float, width: int = 20) -> str:
    ratio = max(0, min(1, current / maximum)) if maximum > 0 else 0
    filled = int(ratio * width)
    return "\u2588" * filled + "\u2591" * (width - filled) + f" {current:.0f}/{maximum:.0f}"


# ── Main Application ──────────────────────────────────────────────

class ControlPanel:
    def __init__(self, root: tk.Tk, port: int = WS_PORT) -> None:
        self.root = root
        self.port = port
        self.root.title("GOAP Control Panel")
        self.root.geometry("1000x700")
        self.root.minsize(800, 500)

        # State
        self.client: EmmatoneClient | None = None
        self.gamer: GamerMode | None = None
        self.hero: HeroMode | None = None
        self.active_mode: str = "none"  # "gamer", "hero", "idle", "none"
        self.connected = False
        self.polling = False
        self._last_debug: dict = {}
        self._jsonl_file = None
        self._thrash_times: list[float] = []

        self._build_ui()

    # ── UI Construction ───────────────────────────────────────────

    def _build_ui(self) -> None:
        # Connection bar
        conn_frame = ttk.Frame(self.root)
        conn_frame.pack(fill=tk.X, padx=5, pady=2)
        self._conn_label = ttk.Label(conn_frame, text="Disconnected", foreground="red")
        self._conn_label.pack(side=tk.LEFT)
        self._conn_btn = ttk.Button(conn_frame, text="Connect", command=self._toggle_connection)
        self._conn_btn.pack(side=tk.LEFT, padx=5)
        self._thrash_label = ttk.Label(conn_frame, text="", foreground="orange")
        self._thrash_label.pack(side=tk.RIGHT)

        # Notebook (tabs)
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)

        self._build_tab_control()
        self._build_tab_auction()
        self._build_tab_goals()
        self._build_tab_debug()
        self._build_tab_commands()

    # ── Tab 1: Control + Live Status ──────────────────────────────

    def _build_tab_control(self) -> None:
        tab = ttk.Frame(self.notebook)
        self.notebook.add(tab, text="Control")

        # Left: mode + personality + commands
        left = ttk.LabelFrame(tab, text="Controls")
        left.pack(side=tk.LEFT, fill=tk.BOTH, padx=5, pady=5)

        # Mode buttons
        mode_frame = ttk.LabelFrame(left, text="Mode")
        mode_frame.pack(fill=tk.X, padx=5, pady=5)

        btn_frame = ttk.Frame(mode_frame)
        btn_frame.pack(fill=tk.X, padx=5, pady=5)
        for text, cmd in [("Gamer", self._on_gamer), ("Hero", self._on_hero),
                          ("Idle", self._on_idle), ("Stop", self._on_stop)]:
            ttk.Button(btn_frame, text=text, command=cmd).pack(side=tk.LEFT, padx=2)

        # Hero tier selector
        tier_frame = ttk.Frame(mode_frame)
        tier_frame.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(tier_frame, text="Hero Tier:").pack(side=tk.LEFT, padx=2)
        self._hero_tier_var = tk.StringVar(value="Diamond")
        ttk.Combobox(tier_frame, textvariable=self._hero_tier_var,
                     values=["Iron", "Diamond", "Netherite"],
                     width=10, state="readonly").pack(side=tk.LEFT, padx=2)

        self._mode_label = ttk.Label(mode_frame, text="Mode: none")
        self._mode_label.pack(padx=5, pady=2)

        # Personality sliders
        pers_frame = ttk.LabelFrame(left, text="Personality")
        pers_frame.pack(fill=tk.X, padx=5, pady=5)

        self._pers_vars: dict[str, tk.DoubleVar] = {}
        for name, default in [("safety", 1.0), ("aggression", 0.8),
                              ("exploration", 0.5), ("resource_hoarding", 0.8)]:
            row = ttk.Frame(pers_frame)
            row.pack(fill=tk.X, padx=5, pady=1)
            ttk.Label(row, text=name.replace("_", " ").title(), width=16).pack(side=tk.LEFT)
            var = tk.DoubleVar(value=default)
            self._pers_vars[name] = var
            scale = ttk.Scale(row, from_=0.0, to=2.0, variable=var, orient=tk.HORIZONTAL,
                              command=lambda v, n=name: self._on_personality_change())
            scale.pack(side=tk.LEFT, fill=tk.X, expand=True)
            lbl = ttk.Label(row, textvariable=var, width=4)
            lbl.pack(side=tk.RIGHT)

        # Quick commands
        cmd_frame = ttk.LabelFrame(left, text="Quick Commands")
        cmd_frame.pack(fill=tk.X, padx=5, pady=5)

        goto_row = ttk.Frame(cmd_frame)
        goto_row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(goto_row, text="Goto:").pack(side=tk.LEFT)
        self._goto_x = ttk.Entry(goto_row, width=6)
        self._goto_x.pack(side=tk.LEFT, padx=1)
        self._goto_y = ttk.Entry(goto_row, width=6)
        self._goto_y.pack(side=tk.LEFT, padx=1)
        self._goto_z = ttk.Entry(goto_row, width=6)
        self._goto_z.pack(side=tk.LEFT, padx=1)
        ttk.Button(goto_row, text="Go", command=self._on_goto).pack(side=tk.LEFT, padx=2)

        btn_row = ttk.Frame(cmd_frame)
        btn_row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(btn_row, text="Cancel", command=self._on_cancel).pack(side=tk.LEFT, padx=2)
        ttk.Button(btn_row, text="Respawn", command=self._on_respawn).pack(side=tk.LEFT, padx=2)
        ttk.Button(btn_row, text="Panic TP", command=self._on_panic).pack(side=tk.LEFT, padx=2)

        # Right: status dashboard
        right = ttk.LabelFrame(tab, text="Status Dashboard")
        right.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True, padx=5, pady=5)

        self._status_text = tk.Text(right, wrap=tk.WORD, font=("Consolas", 10),
                                     state=tk.DISABLED, bg="#1e1e1e", fg="#d4d4d4")
        self._status_text.pack(fill=tk.BOTH, expand=True)

    # ── Tab 2: GOAP Auction ───────────────────────────────────────

    def _build_tab_auction(self) -> None:
        tab = ttk.Frame(self.notebook)
        self.notebook.add(tab, text="Auction")

        self._auction_tick_label = ttk.Label(tab, text="Tick: —")
        self._auction_tick_label.pack(anchor=tk.W, padx=5, pady=2)

        cols = ("action", "score", "raw", "primary", "pers", "collat", "succ", "goal")
        self._auction_tree = ttk.Treeview(tab, columns=cols, show="headings", height=18)

        for col, text, w in [("action", "Action", 140), ("score", "Score", 70),
                             ("raw", "Raw", 70), ("primary", "Primary", 70),
                             ("pers", "Pers", 60), ("collat", "Collat", 70),
                             ("succ", "Succ", 60), ("goal", "Goal", 120)]:
            self._auction_tree.heading(col, text=text)
            self._auction_tree.column(col, width=w, anchor=tk.E if col != "action" and col != "goal" else tk.W)

        self._auction_tree.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self._auction_tree.bind("<<TreeviewSelect>>", self._on_auction_select)

        # Breakdown detail panel
        self._breakdown_text = tk.Text(tab, height=6, wrap=tk.WORD, font=("Consolas", 9),
                                        state=tk.DISABLED, bg="#252526", fg="#9cdcfe")
        self._breakdown_text.pack(fill=tk.X, padx=5, pady=2)

        # Legend
        ttk.Label(tab, text="Score = Raw + Hysteresis(1.5)  |  Raw = (Primary \u00d7 Pers + Collat) \u00d7 Succ  |  \u25b6 = active",
                  font=("Consolas", 8)).pack(anchor=tk.W, padx=5)

    # ── Tab 3: Goal Tree ──────────────────────────────────────────

    def _build_tab_goals(self) -> None:
        tab = ttk.Frame(self.notebook)
        self.notebook.add(tab, text="Goals")

        header = ttk.Frame(tab)
        header.pack(fill=tk.X, padx=5, pady=2)
        self._goals_count_label = ttk.Label(header, text="Goals: —")
        self._goals_count_label.pack(side=tk.LEFT)

        ttk.Label(header, text="Filter:").pack(side=tk.LEFT, padx=(20, 2))
        self._goal_filter_var = tk.StringVar()
        self._goal_filter_var.trace_add("write", lambda *_: self._refresh_goal_tree())
        ttk.Entry(header, textvariable=self._goal_filter_var, width=20).pack(side=tk.LEFT)

        # ── Add/Remove Goal controls ──
        goal_ctrl = ttk.LabelFrame(tab, text="Manage Goals")
        goal_ctrl.pack(fill=tk.X, padx=5, pady=2)

        add_row = ttk.Frame(goal_ctrl)
        add_row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(add_row, text="Item:").pack(side=tk.LEFT)
        self._goal_item_entry = ttk.Entry(add_row, width=18)
        self._goal_item_entry.insert(0, "diamond")
        self._goal_item_entry.pack(side=tk.LEFT, padx=1)
        ttk.Label(add_row, text="Qty:").pack(side=tk.LEFT)
        self._goal_qty_entry = ttk.Entry(add_row, width=4)
        self._goal_qty_entry.insert(0, "1")
        self._goal_qty_entry.pack(side=tk.LEFT, padx=1)
        ttk.Label(add_row, text="Pri:").pack(side=tk.LEFT)
        self._goal_pri_entry = ttk.Entry(add_row, width=4)
        self._goal_pri_entry.insert(0, "10")
        self._goal_pri_entry.pack(side=tk.LEFT, padx=1)
        ttk.Button(add_row, text="Add Goal", command=self._cmd_add_goal).pack(side=tk.LEFT, padx=4)
        ttk.Button(add_row, text="Remove Selected", command=self._cmd_remove_goal).pack(side=tk.LEFT, padx=2)
        ttk.Button(add_row, text="Clear All", command=self._cmd_clear_goals).pack(side=tk.LEFT, padx=2)

        cols = ("goal", "type", "priority", "status")
        self._goals_tree = ttk.Treeview(tab, columns=cols, show="tree headings", height=18)
        self._goals_tree.heading("#0", text="")
        self._goals_tree.column("#0", width=30)
        for col, text, w in [("goal", "Goal ID", 240), ("type", "Type", 100),
                             ("priority", "Priority", 70), ("status", "Status", 100)]:
            self._goals_tree.heading(col, text=text)
            self._goals_tree.column(col, width=w, anchor=tk.E if col == "priority" else tk.W)

        scroll = ttk.Scrollbar(tab, orient=tk.VERTICAL, command=self._goals_tree.yview)
        self._goals_tree.configure(yscrollcommand=scroll.set)
        self._goals_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(5, 0), pady=5)
        scroll.pack(side=tk.RIGHT, fill=tk.Y, pady=5, padx=(0, 5))

    # ── Tab 4: Debug Log + History ────────────────────────────────

    def _build_tab_debug(self) -> None:
        tab = ttk.Frame(self.notebook)
        self.notebook.add(tab, text="Debug")

        # Top: switch history
        hist_frame = ttk.LabelFrame(tab, text="Switch History (last 20)")
        hist_frame.pack(fill=tk.X, padx=5, pady=2)
        self._switch_text = tk.Text(hist_frame, height=5, wrap=tk.NONE, font=("Consolas", 9),
                                     state=tk.DISABLED, bg="#1e1e1e", fg="#d4d4d4")
        self._switch_text.pack(fill=tk.X, padx=2, pady=2)

        # Middle row: combat log + strategy knowledge
        mid = ttk.Frame(tab)
        mid.pack(fill=tk.X, padx=5, pady=2)

        combat_frame = ttk.LabelFrame(mid, text="Combat Log")
        combat_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 2))
        self._combat_text = tk.Text(combat_frame, height=5, wrap=tk.NONE, font=("Consolas", 9),
                                     state=tk.DISABLED, bg="#1e1e1e", fg="#d4d4d4")
        self._combat_text.pack(fill=tk.BOTH, expand=True, padx=2, pady=2)

        strat_frame = ttk.LabelFrame(mid, text="Strategy Knowledge")
        strat_frame.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True, padx=(2, 0))
        self._strat_text = tk.Text(strat_frame, height=5, wrap=tk.WORD, font=("Consolas", 9),
                                    state=tk.DISABLED, bg="#1e1e1e", fg="#d4d4d4")
        self._strat_text.pack(fill=tk.BOTH, expand=True, padx=2, pady=2)

        # World state diff
        diff_frame = ttk.LabelFrame(tab, text="World State Diff")
        diff_frame.pack(fill=tk.X, padx=5, pady=2)
        self._diff_text = tk.Text(diff_frame, height=3, wrap=tk.WORD, font=("Consolas", 9),
                                   state=tk.DISABLED, bg="#1e1e1e", fg="#d4d4d4")
        self._diff_text.pack(fill=tk.X, padx=2, pady=2)

        # JSONL logging controls
        log_frame = ttk.Frame(tab)
        log_frame.pack(fill=tk.X, padx=5, pady=2)
        self._log_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(log_frame, text="Log to file (JSONL)", variable=self._log_var,
                         command=self._toggle_jsonl_logging).pack(side=tk.LEFT)
        self._log_path_label = ttk.Label(log_frame, text="")
        self._log_path_label.pack(side=tk.LEFT, padx=10)

    # ── Tab 5: Commands ──────────────────────────────────────────

    def _build_tab_commands(self) -> None:
        tab = ttk.Frame(self.notebook)
        self.notebook.add(tab, text="Commands")

        # Scrollable area for command sections
        canvas = tk.Canvas(tab, highlightthickness=0)
        scrollbar = ttk.Scrollbar(tab, orient=tk.VERTICAL, command=canvas.yview)
        self._cmd_inner = ttk.Frame(canvas)
        self._cmd_inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=self._cmd_inner, anchor=tk.NW)
        canvas.configure(yscrollcommand=scrollbar.set)

        # Mouse wheel scrolling
        def _on_mousewheel(event):
            canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas.bind_all("<MouseWheel>", _on_mousewheel, add="+")

        # Output panel at bottom (fixed)
        output_frame = ttk.LabelFrame(tab, text="Output")
        output_frame.pack(side=tk.BOTTOM, fill=tk.X, padx=5, pady=2)
        self._cmd_output = tk.Text(output_frame, height=8, wrap=tk.WORD, font=("Consolas", 9),
                                    state=tk.DISABLED, bg="#1e1e1e", fg="#d4d4d4")
        out_scroll = ttk.Scrollbar(output_frame, orient=tk.VERTICAL, command=self._cmd_output.yview)
        self._cmd_output.configure(yscrollcommand=out_scroll.set)
        self._cmd_output.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=2, pady=2)
        out_scroll.pack(side=tk.RIGHT, fill=tk.Y, pady=2)

        # Command sections scroll area
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        inner = self._cmd_inner
        self._cmd_entries: dict[str, ttk.Entry] = {}

        # ── Section 1: Navigation ──
        sec = ttk.LabelFrame(inner, text="Navigation")
        sec.pack(fill=tk.X, padx=5, pady=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Goto:").pack(side=tk.LEFT)
        for label in ("nav_x", "nav_y", "nav_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Button(row, text="Go", command=lambda: self._cmd_goto()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Look At:").pack(side=tk.LEFT)
        for label in ("look_x", "look_y", "look_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Button(row, text="Look", command=lambda: self._cmd_look_at()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Attack r=").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "5")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["attack_r"] = e
        ttk.Button(row, text="Swing", command=lambda: self._cmd_attack()).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Mount", command=lambda: self._run_cmd("mount()", lambda: self.client.mount())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Dismount", command=lambda: self._run_cmd("dismount()", lambda: self.client.dismount())).pack(side=tk.LEFT, padx=2)

        # ── Section 2: Mining & Farming ──
        sec = ttk.LabelFrame(inner, text="Mining & Farming")
        sec.pack(fill=tk.X, padx=5, pady=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Mine:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=18)
        e.insert(0, "minecraft:iron_ore")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["mine_block"] = e
        ttk.Label(row, text="qty:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "1")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["mine_qty"] = e
        ttk.Button(row, text="Mine", command=lambda: self._cmd_mine()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Farm range:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=5)
        e.insert(0, "100")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["farm_range"] = e
        ttk.Button(row, text="Farm", command=lambda: self._cmd_farm()).pack(side=tk.LEFT, padx=2)
        ttk.Label(row, text="Create Farm r:").pack(side=tk.LEFT, padx=(10, 0))
        e = ttk.Entry(row, width=4)
        e.insert(0, "5")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["create_farm_r"] = e
        ttk.Button(row, text="Create", command=lambda: self._cmd_create_farm()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Clear Area:").pack(side=tk.LEFT)
        for label in ("clear_x", "clear_y", "clear_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Label(row, text="r:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "5")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["clear_r"] = e
        ttk.Button(row, text="Clear", command=lambda: self._cmd_clear_area()).pack(side=tk.LEFT, padx=2)

        # ── Section 3: GOAP Goals + Chat ──
        sec = ttk.LabelFrame(inner, text="GOAP Goals + Chat")
        sec.pack(fill=tk.X, padx=5, pady=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Request Item:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=18)
        e.insert(0, "diamond")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["req_item"] = e
        ttk.Label(row, text="qty:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "1")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["req_qty"] = e
        ttk.Label(row, text="pri:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "10")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["req_pri"] = e
        ttk.Button(row, text="Request", command=lambda: self._cmd_request_item()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Chat:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=40)
        e.pack(side=tk.LEFT, padx=1, fill=tk.X, expand=True)
        self._cmd_entries["chat_msg"] = e
        ttk.Button(row, text="Send", command=lambda: self._cmd_chat()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Command:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=40)
        e.insert(0, "time set day")
        e.pack(side=tk.LEFT, padx=1, fill=tk.X, expand=True)
        self._cmd_entries["slash_cmd"] = e
        ttk.Button(row, text="Run", command=lambda: self._cmd_slash()).pack(side=tk.LEFT, padx=2)

        # ── Section 4: Inventory & Storage ──
        sec = ttk.LabelFrame(inner, text="Inventory & Storage")
        sec.pack(fill=tk.X, padx=5, pady=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(row, text="Get Inventory", command=lambda: self._run_cmd(
            "get_inventory()", lambda: self.client.get_inventory())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Full Inventory", command=lambda: self._run_cmd(
            "full_inventory()", lambda: self.client.full_inventory())).pack(side=tk.LEFT, padx=2)
        ttk.Label(row, text="Scan r:").pack(side=tk.LEFT, padx=(10, 0))
        e = ttk.Entry(row, width=4)
        e.insert(0, "32")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["storage_r"] = e
        ttk.Button(row, text="Scan Storage", command=lambda: self._cmd_storage_scan()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(row, text="Overflow Status", command=lambda: self._run_cmd(
            "overflow_status()", lambda: self.client.overflow_status())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Clear Junk", command=lambda: self._run_cmd(
            "overflow_clear_junk()", lambda: self.client.overflow_clear_junk())).pack(side=tk.LEFT, padx=2)
        ttk.Label(row, text="Free slots:").pack(side=tk.LEFT, padx=(10, 0))
        e = ttk.Entry(row, width=4)
        e.insert(0, "5")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["free_target"] = e
        ttk.Button(row, text="Free", command=lambda: self._cmd_free_slots()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Storage Total:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=30)
        e.insert(0, "iron_ingot,diamond")
        e.pack(side=tk.LEFT, padx=1, fill=tk.X, expand=True)
        self._cmd_entries["storage_items"] = e
        ttk.Button(row, text="Query", command=lambda: self._cmd_storage_total()).pack(side=tk.LEFT, padx=2)

        # ── Section 5: Block Primitives ──
        sec = ttk.LabelFrame(inner, text="Block Primitives")
        sec.pack(fill=tk.X, padx=5, pady=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Place:").pack(side=tk.LEFT)
        for label in ("place_x", "place_y", "place_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Button(row, text="Place", command=lambda: self._cmd_place_block()).pack(side=tk.LEFT, padx=2)
        ttk.Label(row, text="Break:").pack(side=tk.LEFT, padx=(10, 0))
        for label in ("break_x", "break_y", "break_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Button(row, text="Break", command=lambda: self._cmd_break_block()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Interact Block:").pack(side=tk.LEFT)
        for label in ("interact_x", "interact_y", "interact_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Button(row, text="Interact", command=lambda: self._cmd_interact_block()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Interact Entity:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=12)
        e.insert(0, "villager")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["ent_type"] = e
        ttk.Label(row, text="r:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "5")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["ent_r"] = e
        ttk.Button(row, text="Interact", command=lambda: self._cmd_interact_entity()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Slot:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=3)
        e.insert(0, "0")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["slot_num"] = e
        ttk.Button(row, text="Select", command=lambda: self._cmd_set_slot()).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Swap Hands", command=lambda: self._run_cmd(
            "swap_hands()", lambda: self.client.swap_hands())).pack(side=tk.LEFT, padx=2)
        self._drop_all_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(row, text="All", variable=self._drop_all_var).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Drop", command=lambda: self._run_cmd(
            f"drop_item(all={self._drop_all_var.get()})",
            lambda: self.client.drop_item(all=self._drop_all_var.get()))).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(row, text="Read Screen", command=lambda: self._run_cmd(
            "read_screen()", lambda: self.client.read_screen())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Close Screen", command=lambda: self._run_cmd(
            "close_screen()", lambda: self.client.close_screen())).pack(side=tk.LEFT, padx=2)
        ttk.Label(row, text="Click Slot:").pack(side=tk.LEFT, padx=(10, 0))
        e = ttk.Entry(row, width=3)
        e.insert(0, "0")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["click_slot"] = e
        ttk.Button(row, text="Click", command=lambda: self._cmd_click_slot()).pack(side=tk.LEFT, padx=2)

        # ── Section 6: World Sensing ──
        sec = ttk.LabelFrame(inner, text="World Sensing")
        sec.pack(fill=tk.X, padx=5, pady=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(row, text="World Info", command=lambda: self._run_cmd(
            "get_world_info()", lambda: self.client.get_world_info())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Get Effects", command=lambda: self._run_cmd(
            "get_effects()", lambda: self.client.get_effects())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Targeted Block", command=lambda: self._run_cmd(
            "get_targeted_block()", lambda: self.client.get_targeted_block())).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Scan Area:").pack(side=tk.LEFT)
        for label in ("scan_x", "scan_y", "scan_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Label(row, text="r:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "32")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["scan_r"] = e
        ttk.Button(row, text="Scan", command=lambda: self._cmd_scan_area()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Get Entities r:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=4)
        e.insert(0, "32")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["ents_r"] = e
        ttk.Label(row, text="type:").pack(side=tk.LEFT)
        e = ttk.Entry(row, width=12)
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["ents_type"] = e
        ttk.Button(row, text="Query", command=lambda: self._cmd_get_entities()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(row, text="Biome at:").pack(side=tk.LEFT)
        for label in ("biome_x", "biome_z"):
            e = ttk.Entry(row, width=6)
            e.pack(side=tk.LEFT, padx=1)
            self._cmd_entries[label] = e
        ttk.Button(row, text="Query", command=lambda: self._cmd_get_biome()).pack(side=tk.LEFT, padx=2)

        # ── Section 7: Torch & Safety ──
        sec = ttk.LabelFrame(inner, text="Torch & Safety")
        sec.pack(fill=tk.X, padx=5, pady=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(row, text="Torch Toggle", command=lambda: self._run_cmd(
            "torch_toggle()", lambda: self.client.torch_toggle())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Torch Status", command=lambda: self._run_cmd(
            "torch_status()", lambda: self.client.torch_status())).pack(side=tk.LEFT, padx=2)
        ttk.Label(row, text="Threshold:").pack(side=tk.LEFT, padx=(10, 0))
        e = ttk.Entry(row, width=3)
        e.insert(0, "7")
        e.pack(side=tk.LEFT, padx=1)
        self._cmd_entries["torch_thresh"] = e
        ttk.Button(row, text="Set", command=lambda: self._cmd_torch_threshold()).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(row, text="Panic Status", command=lambda: self._run_cmd(
            "panic_teleport_status()", lambda: self.client.panic_teleport_status())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Force Panic", command=lambda: self._run_cmd(
            "force_panic_teleport()", lambda: self.client.force_panic_teleport())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Enable Panic", command=lambda: self._run_cmd(
            "enable_panic_teleport()", lambda: self.client.enable_panic_teleport())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Disable Panic", command=lambda: self._run_cmd(
            "disable_panic_teleport()", lambda: self.client.disable_panic_teleport())).pack(side=tk.LEFT, padx=2)

        row = ttk.Frame(sec)
        row.pack(fill=tk.X, padx=5, pady=2)
        ttk.Button(row, text="Cancel Task", command=lambda: self._run_cmd(
            "cancel()", lambda: self.client.cancel())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Respawn", command=lambda: self._run_cmd(
            "respawn()", lambda: self.client.respawn())).pack(side=tk.LEFT, padx=2)
        ttk.Button(row, text="Use Item", command=lambda: self._run_cmd(
            "use_item()", lambda: self.client.use_item())).pack(side=tk.LEFT, padx=2)

    # ── Command helpers ────────────────────────────────────────────

    def _run_cmd(self, label: str, fn) -> None:
        """Execute a client command in background, show result in output."""
        if not self._ensure_connected():
            return

        def worker():
            try:
                result = fn()
                text = json.dumps(result, indent=2, default=str)
                self.root.after(0, lambda: self._append_output(f"> {label}\n{text}"))
            except Exception as e:
                self.root.after(0, lambda: self._append_output(f"> {label}\nERROR: {e}"))

        threading.Thread(target=worker, daemon=True).start()

    def _append_output(self, text: str) -> None:
        """Append text to the command output panel."""
        self._cmd_output.config(state=tk.NORMAL)
        self._cmd_output.insert(tk.END, text + "\n\n")
        self._cmd_output.see(tk.END)
        self._cmd_output.config(state=tk.DISABLED)

    def _cmd_goto(self) -> None:
        try:
            x = int(self._cmd_entries["nav_x"].get())
            y_str = self._cmd_entries["nav_y"].get().strip()
            y = int(y_str) if y_str else None
            z = int(self._cmd_entries["nav_z"].get())
            self._run_cmd(f"goto({x}, {y}, {z})", lambda: self.client.goto(x, y, z))
        except ValueError:
            self._append_output("> goto: invalid coordinates")

    def _cmd_look_at(self) -> None:
        try:
            x = int(self._cmd_entries["look_x"].get())
            y = int(self._cmd_entries["look_y"].get())
            z = int(self._cmd_entries["look_z"].get())
            self._run_cmd(f"look_at({x}, {y}, {z})", lambda: self.client.look_at(x, y, z))
        except ValueError:
            self._append_output("> look_at: invalid coordinates")

    def _cmd_attack(self) -> None:
        try:
            r = int(self._cmd_entries["attack_r"].get())
            self._run_cmd(f"attack(radius={r})", lambda: self.client.attack(radius=r))
        except ValueError:
            self._append_output("> attack: invalid radius")

    def _cmd_mine(self) -> None:
        block = self._cmd_entries["mine_block"].get().strip()
        try:
            qty = int(self._cmd_entries["mine_qty"].get())
        except ValueError:
            qty = 1
        self._run_cmd(f"mine({block!r}, {qty})", lambda: self.client.mine(block, qty))

    def _cmd_farm(self) -> None:
        try:
            r = int(self._cmd_entries["farm_range"].get())
        except ValueError:
            r = 100
        self._run_cmd(f"farm(range={r})", lambda: self.client.farm(range=r))

    def _cmd_create_farm(self) -> None:
        try:
            r = int(self._cmd_entries["create_farm_r"].get())
        except ValueError:
            r = 5
        self._run_cmd(f"create_farm(radius={r})", lambda: self.client.create_farm(radius=r))

    def _cmd_clear_area(self) -> None:
        try:
            x = int(self._cmd_entries["clear_x"].get())
            y = int(self._cmd_entries["clear_y"].get())
            z = int(self._cmd_entries["clear_z"].get())
            r = int(self._cmd_entries["clear_r"].get())
            self._run_cmd(f"clear_area({x}, {y}, {z}, radius={r})",
                          lambda: self.client.clear_area(x, y, z, radius=r))
        except ValueError:
            self._append_output("> clear_area: invalid coordinates/radius")

    def _cmd_request_item(self) -> None:
        item = self._cmd_entries["req_item"].get().strip()
        try:
            qty = int(self._cmd_entries["req_qty"].get())
        except ValueError:
            qty = 1
        try:
            pri = float(self._cmd_entries["req_pri"].get())
        except ValueError:
            pri = 10.0
        self._run_cmd(f"request_item({item!r}, {qty}, {pri})",
                      lambda: self.client.request_item(item, qty, pri))

    def _cmd_add_goal(self) -> None:
        item = self._goal_item_entry.get().strip()
        if not item:
            self._append_output("> add_goal: no item specified")
            return
        try:
            qty = int(self._goal_qty_entry.get())
        except ValueError:
            qty = 1
        try:
            pri = float(self._goal_pri_entry.get())
        except ValueError:
            pri = 10.0
        item_id = item if ":" in item else f"minecraft:{item}"
        clean = item.replace("minecraft:", "")
        goal_id = f"get_{clean}_{qty}"
        self._run_cmd(f"add_goal({goal_id!r})",
                      lambda: self.client.add_goal(goal_id, "have_item", pri,
                                                    {"item": item_id, "count": qty}))

    def _cmd_remove_goal(self) -> None:
        sel = self._goals_tree.selection()
        if not sel:
            self._append_output("> remove_goal: no goal selected in tree")
            return
        values = self._goals_tree.item(sel[0], "values")
        if not values:
            return
        goal_id = values[0]  # first column is goal ID
        # Don't remove survival goals
        if goal_id in ("survive", "stay_fed", "be_lit"):
            self._append_output(f"> remove_goal: cannot remove survival goal '{goal_id}'")
            return
        self._run_cmd(f"remove_goal({goal_id!r})",
                      lambda: self.client.remove_goal(goal_id))

    def _cmd_clear_goals(self) -> None:
        self._run_cmd("set_goap_goals([])", lambda: self.client.set_goap_goals([]))

    def _cmd_chat(self) -> None:
        msg = self._cmd_entries["chat_msg"].get().strip()
        if msg:
            self._run_cmd(f"send_chat({msg!r})", lambda: self.client.send_chat(msg))

    def _cmd_slash(self) -> None:
        cmd = self._cmd_entries["slash_cmd"].get().strip()
        if cmd:
            self._run_cmd(f"send_command({cmd!r})", lambda: self.client.send_command(cmd))

    def _cmd_storage_scan(self) -> None:
        try:
            r = int(self._cmd_entries["storage_r"].get())
        except ValueError:
            r = 32
        self._run_cmd(f"storage_scan(radius={r})", lambda: self.client.storage_scan(radius=r))

    def _cmd_free_slots(self) -> None:
        try:
            target = int(self._cmd_entries["free_target"].get())
        except ValueError:
            target = 5
        self._run_cmd(f"overflow_free_slots(target={target})",
                      lambda: self.client.overflow_free_slots(target=target))

    def _cmd_storage_total(self) -> None:
        items_str = self._cmd_entries["storage_items"].get().strip()
        items = [s.strip() for s in items_str.split(",") if s.strip()]
        if items:
            self._run_cmd(f"storage_total({items})", lambda: self.client.storage_total(items))

    def _cmd_place_block(self) -> None:
        try:
            x = int(self._cmd_entries["place_x"].get())
            y = int(self._cmd_entries["place_y"].get())
            z = int(self._cmd_entries["place_z"].get())
            self._run_cmd(f"place_block({x}, {y}, {z})", lambda: self.client.place_block(x, y, z))
        except ValueError:
            self._append_output("> place_block: invalid coordinates")

    def _cmd_break_block(self) -> None:
        try:
            x = int(self._cmd_entries["break_x"].get())
            y = int(self._cmd_entries["break_y"].get())
            z = int(self._cmd_entries["break_z"].get())
            self._run_cmd(f"break_block({x}, {y}, {z})", lambda: self.client.break_block(x, y, z))
        except ValueError:
            self._append_output("> break_block: invalid coordinates")

    def _cmd_interact_block(self) -> None:
        try:
            x = int(self._cmd_entries["interact_x"].get())
            y = int(self._cmd_entries["interact_y"].get())
            z = int(self._cmd_entries["interact_z"].get())
            self._run_cmd(f"interact_block({x}, {y}, {z})", lambda: self.client.interact_block(x, y, z))
        except ValueError:
            self._append_output("> interact_block: invalid coordinates")

    def _cmd_interact_entity(self) -> None:
        etype = self._cmd_entries["ent_type"].get().strip()
        try:
            r = int(self._cmd_entries["ent_r"].get())
        except ValueError:
            r = 5
        self._run_cmd(f"interact_entity({etype!r}, radius={r})",
                      lambda: self.client.interact_entity(etype, radius=r))

    def _cmd_set_slot(self) -> None:
        try:
            slot = int(self._cmd_entries["slot_num"].get())
            self._run_cmd(f"set_slot({slot})", lambda: self.client.set_slot(slot=slot))
        except ValueError:
            self._append_output("> set_slot: invalid slot number")

    def _cmd_click_slot(self) -> None:
        try:
            slot = int(self._cmd_entries["click_slot"].get())
            self._run_cmd(f"click_slot({slot})", lambda: self.client.click_slot(slot=slot))
        except ValueError:
            self._append_output("> click_slot: invalid slot number")

    def _cmd_scan_area(self) -> None:
        try:
            x = int(self._cmd_entries["scan_x"].get())
            y = int(self._cmd_entries["scan_y"].get())
            z = int(self._cmd_entries["scan_z"].get())
            r = int(self._cmd_entries["scan_r"].get())
            self._run_cmd(f"scan_area({x}, {y}, {z}, radius={r})",
                          lambda: self.client.scan_area(x, y, z, radius=r))
        except ValueError:
            self._append_output("> scan_area: invalid coordinates/radius")

    def _cmd_get_entities(self) -> None:
        try:
            r = int(self._cmd_entries["ents_r"].get())
        except ValueError:
            r = 32
        etype = self._cmd_entries["ents_type"].get().strip() or None
        self._run_cmd(f"get_entities(radius={r}, type={etype!r})",
                      lambda: self.client.get_entities(radius=r, type=etype))

    def _cmd_get_biome(self) -> None:
        try:
            x = int(self._cmd_entries["biome_x"].get())
            z = int(self._cmd_entries["biome_z"].get())
            self._run_cmd(f"get_biome({x}, {z})", lambda: self.client.get_biome(x, z))
        except ValueError:
            self._append_output("> get_biome: invalid coordinates")

    def _cmd_torch_threshold(self) -> None:
        try:
            t = int(self._cmd_entries["torch_thresh"].get())
            self._run_cmd(f"torch_set_threshold({t})", lambda: self.client.torch_set_threshold(t))
        except ValueError:
            self._append_output("> torch_set_threshold: invalid number")

    # ── Connection ────────────────────────────────────────────────

    def _toggle_connection(self) -> None:
        if self.connected:
            self._disconnect()
        else:
            self._try_connect()

    def _try_connect(self) -> None:
        self._conn_btn.config(state=tk.DISABLED)
        self._conn_label.config(text="Connecting...", foreground="orange")

        def do_connect():
            try:
                client = EmmatoneClient("127.0.0.1", self.port)
                client.connect(reconnect=False)
                if not client.wait_for_connection(timeout=10.0):
                    client.disconnect()
                    raise ConnectionError("Timed out waiting for WebSocket handshake")
                self.client = client
                self.gamer = GamerMode(client)
                self.hero = HeroMode(client)
                self.connected = True
                self.root.after(0, self._on_connected)
            except Exception as exc:
                msg = str(exc)
                self.root.after(0, lambda msg=msg: self._on_connect_error(msg))

        threading.Thread(target=do_connect, daemon=True).start()

    def _disconnect(self) -> None:
        self.polling = False
        self.connected = False
        if self.client:
            try:
                self.client.disconnect()
            except Exception:
                pass
            self.client = None
        self.gamer = None
        self.hero = None
        self._conn_label.config(text="Disconnected", foreground="red")
        self._conn_btn.config(text="Connect", state=tk.NORMAL)

    def _on_connected(self) -> None:
        self._conn_label.config(text=f"Connected (port {self.port})", foreground="green")
        self._conn_btn.config(text="Disconnect", state=tk.NORMAL)
        self._start_polling()

    def _on_connect_error(self, err: str) -> None:
        self._conn_label.config(text=f"Disconnected: {err}", foreground="red")
        self._conn_btn.config(text="Connect", state=tk.NORMAL)

    # ── Polling ───────────────────────────────────────────────────

    def _start_polling(self) -> None:
        if self.polling:
            return
        self.polling = True
        self._poll()

    def _poll(self) -> None:
        if not self.polling or not self.connected:
            return

        def do_poll():
            try:
                data = self.client.goap_debug(include_world_state=True)
                self.root.after(0, lambda: self._update_all(data))
            except Exception as e:
                self.root.after(0, lambda: self._on_poll_error(str(e)))

        threading.Thread(target=do_poll, daemon=True).start()
        self.root.after(POLL_INTERVAL_MS, self._poll)

    def _on_poll_error(self, err: str) -> None:
        self._conn_label.config(text=f"Poll error: {err}", foreground="orange")
        if "closed" in err.lower() or "connect" in err.lower():
            self.connected = False
            self.polling = False
            self._conn_btn.config(text="Connect", state=tk.NORMAL)

    # ── Update all panels from debug data ─────────────────────────

    def _update_all(self, raw: dict) -> None:
        data = raw.get("data", raw)
        self._last_debug = data

        # JSONL logging
        if self._jsonl_file:
            try:
                entry = {"ts": time.time(), "iso": datetime.now().isoformat(), **data}
                self._jsonl_file.write(json.dumps(entry, default=str) + "\n")
                self._jsonl_file.flush()
            except Exception:
                pass

        self._update_status(data)
        self._update_auction(data)
        self._update_goals(data)
        self._update_debug(data)
        self._check_thrashing(data)

    # ── Tab 1 updates ─────────────────────────────────────────────

    def _update_status(self, data: dict) -> None:
        ws = data.get("world_state", data.get("last_world_state", {}))
        active = data.get("active_action", "none")
        auction = data.get("auction", [])
        goals = data.get("goals", [])

        hp = ws.get("health", 0)
        max_hp = ws.get("maxHealth", 20)
        hunger = ws.get("hunger", 0)
        pos_x = ws.get("posX", 0)
        pos_y = ws.get("posY", 0)
        pos_z = ws.get("posZ", 0)
        dim = ws.get("dimension", "?")
        light = ws.get("lightLevel", 0)
        threats = len(ws.get("threats", []))
        free_slots = ws.get("freeSlots", 0)
        food_count = ws.get("foodItemCount", 0)

        # Find top 2 scores
        top_score = auction[0]["score"] if auction else 0
        runner = auction[1]["action"] if len(auction) > 1 and auction[1].get("score", 0) > 0 else "—"
        runner_score = auction[1]["score"] if len(auction) > 1 else 0

        user_goals = [g for g in goals if not g.get("is_derived")]
        derived_goals = [g for g in goals if g.get("is_derived")]

        lines = [
            f"  HP:     {bar_text(hp, max_hp)}",
            f"  Food:   {bar_text(hunger, 20)}  ({food_count} items)",
            f"  Pos:    {pos_x:.0f}, {pos_y:.0f}, {pos_z:.0f}  ({dim.split(':')[-1]})",
            f"  Light:  {light}  |  Threats: {threats}  |  Inv: {free_slots} free",
            f"",
            f"  Active: {active} ({top_score:.2f})",
            f"  Runner: {runner} ({runner_score:.2f})",
            f"",
            f"  Mode:   {self.active_mode}",
            f"  Goals:  {len(user_goals)} user + {len(derived_goals)} derived",
        ]

        # Add mode-specific info
        if self.active_mode == "gamer" and self.gamer:
            s = self.gamer.status
            elapsed = time.time() - s["last_refresh"] if s["last_refresh"] else 0
            lines.append(f"  Refresh: {elapsed:.0f}s ago")

        self._set_text(self._status_text, "\n".join(lines))

    # ── Tab 2 updates ─────────────────────────────────────────────

    def _update_auction(self, data: dict) -> None:
        tick = data.get("tick", 0)
        self._auction_tick_label.config(text=f"Tick: {tick}")

        auction = data.get("auction", [])
        active_name = data.get("active_action", "")

        self._auction_tree.delete(*self._auction_tree.get_children())
        for a in auction:
            name = a.get("action", "?")
            score = a.get("score", 0)
            raw = a.get("raw_score", 0)
            is_active = a.get("is_active", False)
            bd = a.get("breakdown") or {}

            prefix = "\u25b6 " if is_active else "  "
            values = (
                prefix + name,
                format_score(score),
                format_score(raw),
                format_score(bd.get("primary")),
                format_score(bd.get("personality_bias")),
                format_score(bd.get("collateral")),
                format_score(bd.get("success_rate")),
                bd.get("primary_goal", "—") if bd else "—",
            )

            tag = "active" if is_active else ("viable" if score > 0 else "inactive")
            iid = self._auction_tree.insert("", tk.END, values=values, tags=(tag,))

        # Tag colors
        self._auction_tree.tag_configure("active", foreground="#4ec9b0")
        self._auction_tree.tag_configure("viable", foreground="#d4d4d4")
        self._auction_tree.tag_configure("inactive", foreground="#6a6a6a")

    def _on_auction_select(self, event) -> None:
        sel = self._auction_tree.selection()
        if not sel:
            return
        idx = self._auction_tree.index(sel[0])
        auction = self._last_debug.get("auction", [])
        if idx < len(auction):
            bd = auction[idx].get("breakdown", {})
            name = auction[idx].get("action", "?")
            score = auction[idx].get("score", 0)
            raw = auction[idx].get("raw_score", 0)

            # Build "why" explanation
            lines = [f"=== {name} (score {score:.2f}) ==="]
            if bd:
                primary = bd.get("primary", 0)
                pers = bd.get("personality_bias", 1)
                collat = bd.get("collateral", 0)
                succ = bd.get("success_rate", 1)
                raw_combined = bd.get("raw_combined", 0)
                goal = bd.get("primary_goal", "?")

                lines.append(f"Goal: {goal}")
                lines.append(f"Primary: {primary:.3f}  x  Personality: {pers:.3f}  =  {primary * pers:.3f}")
                lines.append(f"  + Collateral: {collat:.3f}")
                lines.append(f"  x  Success rate: {succ:.3f}")
                lines.append(f"  = Raw: {raw_combined:.3f}")
                if auction[idx].get("is_active"):
                    lines.append(f"  + Hysteresis: 1.50  = Final: {score:.3f}")
                lines.append("")
                lines.append("Full breakdown:")
                lines.append(json.dumps(bd, indent=2, default=str))

            self._set_text(self._breakdown_text, "\n".join(lines))

    # ── Tab 3 updates ─────────────────────────────────────────────

    def _update_goals(self, data: dict) -> None:
        goals = data.get("goals", [])
        user_goals = [g for g in goals if not g.get("is_derived")]
        derived_goals = [g for g in goals if g.get("is_derived")]
        self._goals_count_label.config(text=f"Goals: {len(user_goals)} user + {len(derived_goals)} derived")
        self._refresh_goal_tree()

    def _refresh_goal_tree(self) -> None:
        goals = self._last_debug.get("goals", [])
        filter_text = self._goal_filter_var.get().lower() if hasattr(self, "_goal_filter_var") else ""

        self._goals_tree.delete(*self._goals_tree.get_children())

        # Group by parent
        user_goals = [g for g in goals if not g.get("is_derived")]
        derived_by_parent: dict[str, list] = {}
        for g in goals:
            if g.get("is_derived") and g.get("parent_goal"):
                derived_by_parent.setdefault(g["parent_goal"], []).append(g)

        for g in user_goals:
            gid = g.get("id", "?")
            if filter_text and filter_text not in gid.lower():
                # Check if any child matches
                children = derived_by_parent.get(gid, [])
                if not any(filter_text in c.get("id", "").lower() for c in children):
                    continue

            priority = g.get("priority", 0)
            gtype = g.get("type", "?")
            score = g.get("score", 0)
            status = "\u25cf satisfied" if score < 0.01 and priority > 0 else f"rel: {score:.2f}"

            tag = "survival" if gid in ("survive", "stay_fed", "be_lit") else "user"
            parent_iid = self._goals_tree.insert("", tk.END, text="",
                                                  values=(gid, gtype, f"{priority:.1f}", status),
                                                  tags=(tag,), open=False)

            # Insert derived children
            for child in derived_by_parent.get(gid, []):
                cid = child.get("id", "?")
                if filter_text and filter_text not in cid.lower() and filter_text not in gid.lower():
                    continue
                cpri = child.get("priority", 0)
                ctype = child.get("type", "?")
                cscore = child.get("score", 0)
                cstatus = "\u25cf satisfied" if cscore < 0.01 and cpri > 0 else f"rel: {cscore:.2f}"
                self._goals_tree.insert(parent_iid, tk.END, text="",
                                         values=(cid, ctype, f"{cpri:.1f}", cstatus),
                                         tags=("derived",))

        self._goals_tree.tag_configure("survival", foreground="#569cd6")
        self._goals_tree.tag_configure("user", foreground="#d4d4d4")
        self._goals_tree.tag_configure("derived", foreground="#808080")

    # ── Tab 4 updates ─────────────────────────────────────────────

    def _update_debug(self, data: dict) -> None:
        # Switch history
        history = data.get("switch_history", [])
        lines = []
        for h in history:
            ts_ms = h.get("timestamp_ms", 0)
            ts_str = datetime.fromtimestamp(ts_ms / 1000).strftime("%H:%M:%S") if ts_ms else "?"
            fr = h.get("from", "?")
            to = h.get("to", "?")
            fs = h.get("from_score", 0)
            ts = h.get("to_score", 0)
            reason = h.get("reason", "?")
            lines.append(f"  {ts_str}  {fr} \u2192 {to}  ({fs:.1f} \u2192 {ts:.1f})  [{reason}]")
        self._set_text(self._switch_text, "\n".join(lines) or "  No switches yet")

        # Combat log
        combat = data.get("combat_log", [])
        clines = []
        for entry in combat[-5:]:
            etype = entry.get("type", "?")
            edata = entry.get("data", {})
            if etype == "damage_taken":
                summary = f"dmg={edata.get('amount', '?')} src={edata.get('source_type', '?')}"
            elif etype == "damage_dealt":
                summary = f"hit {edata.get('target_type', '?')} hp={edata.get('target_health', '?')}"
            elif etype == "defense_decision":
                summary = f"action={edata.get('action', '?')} danger={edata.get('dangerousness', '?')}"
            else:
                summary = str(edata)[:60]
            clines.append(f"  {etype:<20} {summary}")
        self._set_text(self._combat_text, "\n".join(clines) or "  No combat events")

        # Strategy knowledge
        knowledge = data.get("strategy_knowledge", {})
        rates = knowledge.get("success_rates", {})
        if rates:
            parts = []
            for k, v in sorted(rates.items()):
                marker = "\u26a0" if v < 0.7 else " "
                parts.append(f"  {marker} {k}: {v:.2f}")
            obstructions = knowledge.get("obstruction_count", 0)
            unreachable = knowledge.get("unreachable_count", 0)
            parts.append(f"\n  Obstructions: {obstructions}  |  Unreachable: {unreachable}")
            self._set_text(self._strat_text, "\n".join(parts))
        else:
            self._set_text(self._strat_text, "  No strategy data yet")

        # World state diff
        diff = data.get("world_state_diff", {})
        if diff:
            parts = [f"  {k}: {v}" for k, v in diff.items()]
            self._set_text(self._diff_text, "  |  ".join(parts))
        else:
            self._set_text(self._diff_text, "  No changes since last poll")

    # ── Thrash detection ──────────────────────────────────────────

    def _check_thrashing(self, data: dict) -> None:
        history = data.get("switch_history", [])
        if not history:
            self._thrash_label.config(text="")
            return

        now_ms = time.time() * 1000
        recent = [h for h in history if now_ms - h.get("timestamp_ms", 0) < 10_000]

        if len(recent) >= 3:
            actions = set()
            for h in recent:
                actions.add(h.get("from", ""))
                actions.add(h.get("to", ""))
            actions.discard("")
            self._thrash_label.config(
                text=f"\u26a0 THRASHING: {len(recent)} switches in 10s ({', '.join(actions)})",
                foreground="red")
        else:
            self._thrash_label.config(text="")

    # ── Mode button handlers ──────────────────────────────────────

    def _on_gamer(self) -> None:
        if not self._ensure_connected():
            return
        self._deactivate_current()
        self.gamer.activate()
        self.active_mode = "gamer"
        self._mode_label.config(text="Mode: Gamer")
        # Update personality sliders to match
        for k, v in self.gamer.client.set_personality.__func__.__defaults__ or []:
            pass  # personality was already set by activate()
        self._sync_sliders_from_mode("gamer")

    def _on_hero(self) -> None:
        if not self._ensure_connected():
            return
        self._deactivate_current()
        tier = self._hero_tier_var.get().lower()
        self.hero.activate(tier=tier)
        self.active_mode = "hero"
        self._mode_label.config(text=f"Mode: Hero ({tier.title()})")
        self._sync_sliders_from_mode("hero")

    def _on_idle(self) -> None:
        if not self._ensure_connected():
            return
        self._deactivate_current()
        self.client.cancel()
        self.client.set_goap_goals([], enabled=True)
        self.active_mode = "idle"
        self._mode_label.config(text="Mode: Idle")

    def _on_stop(self) -> None:
        if not self._ensure_connected():
            return
        self._deactivate_current()
        self.client.cancel()
        self.client.set_goap_goals([], enabled=False)
        self.active_mode = "none"
        self._mode_label.config(text="Mode: Stopped")

    def _deactivate_current(self) -> None:
        if self.active_mode == "gamer" and self.gamer:
            self.gamer.deactivate()
        elif self.active_mode == "hero" and self.hero:
            self.hero.deactivate()

    def _sync_sliders_from_mode(self, mode: str) -> None:
        if mode == "gamer":
            from gamer.gamer_mode import PERSONALITY_OVERWORLD as p
        elif mode == "hero":
            from gamer.hero_mode import PERSONALITY_HERO as p
        else:
            return
        for k, v in p.items():
            if k in self._pers_vars:
                self._pers_vars[k].set(v)

    # ── Personality slider handler ────────────────────────────────

    def _on_personality_change(self) -> None:
        if not self.connected or not self.client:
            return
        weights = {k: round(v.get(), 2) for k, v in self._pers_vars.items()}
        try:
            self.client.set_personality(weights)
        except Exception:
            pass

    # ── Quick command handlers ────────────────────────────────────

    def _on_goto(self) -> None:
        if not self._ensure_connected():
            return
        try:
            x = int(self._goto_x.get())
            y = int(self._goto_y.get()) if self._goto_y.get() else None
            z = int(self._goto_z.get())
            self.client.goto(x, y, z)
        except ValueError:
            messagebox.showwarning("Goto", "Enter valid integer coordinates")

    def _on_cancel(self) -> None:
        if self._ensure_connected():
            self.client.cancel()

    def _on_respawn(self) -> None:
        if self._ensure_connected():
            self.client.respawn()

    def _on_panic(self) -> None:
        if self._ensure_connected():
            self.client.force_panic_teleport()

    # ── JSONL logging ─────────────────────────────────────────────

    def _toggle_jsonl_logging(self) -> None:
        if self._log_var.get():
            log_dir = Path(__file__).resolve().parent.parent / "logs"
            log_dir.mkdir(exist_ok=True)
            fname = f"goap_session_{datetime.now().strftime('%Y%m%d_%H%M%S')}.jsonl"
            fpath = log_dir / fname
            self._jsonl_file = open(fpath, "a", encoding="utf-8")
            self._log_path_label.config(text=str(fpath))
            log.info("JSONL logging started: %s", fpath)
        else:
            if self._jsonl_file:
                self._jsonl_file.close()
                self._jsonl_file = None
            self._log_path_label.config(text="")
            log.info("JSONL logging stopped")

    # ── Helpers ───────────────────────────────────────────────────

    def _ensure_connected(self) -> bool:
        if not self.connected or not self.client:
            messagebox.showwarning("Not Connected", "Not connected to bridge. Click Reconnect.")
            return False
        return True

    @staticmethod
    def _set_text(widget: tk.Text, content: str) -> None:
        widget.config(state=tk.NORMAL)
        widget.delete("1.0", tk.END)
        widget.insert("1.0", content)
        widget.config(state=tk.DISABLED)

    def on_close(self) -> None:
        self.polling = False
        if self._jsonl_file:
            self._jsonl_file.close()
        if self.client:
            try:
                self.client.disconnect()
            except Exception:
                pass
        self.root.destroy()


# ── Entry point ───────────────────────────────────────────────────

def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

    port = WS_PORT
    if "--port" in sys.argv:
        idx = sys.argv.index("--port")
        if idx + 1 < len(sys.argv):
            port = int(sys.argv[idx + 1])

    root = tk.Tk()
    app = ControlPanel(root, port=port)
    root.protocol("WM_DELETE_WINDOW", app.on_close)
    root.mainloop()


if __name__ == "__main__":
    main()
