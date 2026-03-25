// Emma Twitch Control Panel

const API = '';
let effects = [];
let config = {};
let rewards = [];

// --- Tab Navigation ---
document.querySelectorAll('.tab').forEach(tab => {
	tab.addEventListener('click', () => {
		document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
		document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
		tab.classList.add('active');
		document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
		// Load rewards when switching to that tab
		if (tab.dataset.tab === 'rewards') loadRewards();
	});
});

// --- Init ---
async function init() {
	await Promise.all([loadEffects(), loadConfig(), loadStatus()]);
	setInterval(loadStatus, 10000);
}

// --- Effects ---
async function loadEffects() {
	try {
		const res = await fetch(API + '/api/effects');
		effects = await res.json();
		renderEffects();
	} catch (e) { console.error('Failed to load effects:', e); }
}

function renderEffects() {
	const search = (document.getElementById('effect-search').value || '').toLowerCase();
	const enabledOnly = document.getElementById('show-enabled-only').checked;
	const tbody = document.getElementById('effects-body');

	const filtered = effects.filter(e => {
		if (search && !e.id.toLowerCase().includes(search)) return false;
		if (enabledOnly && !e.enabled) return false;
		return true;
	});

	tbody.innerHTML = filtered.map(e => `
		<tr>
			<td><code>${e.id}</code></td>
			<td>
				<label class="toggle">
					<input type="checkbox" ${e.enabled ? 'checked' : ''} onchange="toggleEffect('${e.id}', this.checked)">
					<span class="slider"></span>
				</label>
			</td>
			<td>
				<input type="number" value="${e.cooldown}" min="0" onchange="setCooldown('${e.id}', this.value)">
			</td>
			<td>
				<button class="btn btn-sm btn-test" onclick="testEffect('${e.id}', this)">Test</button>
			</td>
		</tr>
	`).join('');

	document.getElementById('effect-count').textContent = effects.length + ' effects';
}

async function toggleEffect(id, enabled) {
	await fetch(API + '/api/effects', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ id, enabled })
	});
}

async function setCooldown(id, cooldown) {
	await fetch(API + '/api/effects', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ id, cooldown: parseInt(cooldown) })
	});
}

async function testEffect(id, btn) {
	const res = await fetch(API + '/api/test/' + id, { method: 'POST' });
	if (res.ok && btn) {
		btn.textContent = 'Fired!';
		btn.disabled = true;
		setTimeout(() => { btn.textContent = 'Test'; btn.disabled = false; }, 2000);
	}
}

document.getElementById('effect-search').addEventListener('input', renderEffects);
document.getElementById('show-enabled-only').addEventListener('change', renderEffects);

// --- Config & Status ---
async function loadConfig() {
	try {
		const res = await fetch(API + '/api/config');
		config = await res.json();
		document.getElementById('global-cooldown').value = config.global_cooldown_seconds;
		const channelEl = document.getElementById('twitch-channel');
		const connectBtn = document.getElementById('twitch-connect-btn');
		if (config.twitch_configured && config.channel_name) {
			channelEl.textContent = config.channel_name;
			connectBtn.textContent = 'Reconnect';
		} else {
			channelEl.textContent = 'Not configured';
			connectBtn.textContent = 'Connect to Twitch';
		}
		renderMappings();
		renderBitTiers();
	} catch (e) { console.error('Failed to load config:', e); }
}

async function loadStatus() {
	try {
		const res = await fetch(API + '/api/twitch/status');
		const status = await res.json();
		const badge = document.getElementById('twitch-status');
		if (status.connected) {
			badge.textContent = 'Connected: ' + (status.channel_name || 'Twitch');
			badge.className = 'status-badge connected';
		} else if (status.configured) {
			badge.textContent = 'Disconnected';
			badge.className = 'status-badge disconnected';
		} else {
			badge.textContent = 'Not Configured';
			badge.className = 'status-badge disconnected';
		}
	} catch (e) { /* server not ready */ }
}

async function saveSettings() {
	await fetch(API + '/api/config', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({
			global_cooldown_seconds: parseInt(document.getElementById('global-cooldown').value)
		})
	});
	alert('Settings saved');
}

// --- Twitch OAuth ---
async function connectTwitch() {
	try {
		const res = await fetch(API + '/api/twitch/connect');
		const data = await res.json();
		if (data.auth_url) {
			window.open(data.auth_url, '_blank', 'width=600,height=800');
		}
	} catch (e) {
		alert('Failed to start OAuth flow: ' + e.message);
	}
}

// --- Twitch Rewards ---
async function loadRewards() {
	const loading = document.getElementById('rewards-loading');
	const table = document.getElementById('rewards-table');
	const errorEl = document.getElementById('rewards-error');
	loading.style.display = 'block';
	table.style.display = 'none';
	errorEl.style.display = 'none';

	try {
		const res = await fetch(API + '/api/twitch/rewards');
		const data = await res.json();

		if (data.error) {
			errorEl.textContent = data.error;
			errorEl.style.display = 'block';
		}

		rewards = data.rewards || [];
		if (rewards.length > 0) {
			renderRewards();
			table.style.display = 'table';
		}
	} catch (e) {
		errorEl.textContent = 'Failed to load rewards: ' + e.message;
		errorEl.style.display = 'block';
	}
	loading.style.display = 'none';
}

function renderRewards() {
	const tbody = document.getElementById('rewards-body');
	// Build effect options for the dropdowns
	const effectOptions = effects.map(e => `<option value="${e.id}">${e.id}</option>`).join('');

	tbody.innerHTML = rewards.map(r => {
		const mappedEffect = r.mapped_effect || '';
		return `
		<tr>
			<td>
				<strong>${r.title}</strong>
				${r.prompt ? `<br><small style="color:#888">${r.prompt}</small>` : ''}
			</td>
			<td>${r.cost} pts</td>
			<td>
				<select class="reward-effect-select" data-reward-id="${r.id}" style="padding:4px 8px; background:#0f3460; border:1px solid #444; border-radius:4px; color:#e0e0e0; min-width:180px">
					<option value="">-- Not Mapped --</option>
					${effectOptions}
				</select>
			</td>
			<td>
				<button class="btn btn-sm" onclick="saveRewardMapping('${r.id}', this)">Save</button>
			</td>
		</tr>`;
	}).join('');

	// Set current mapped values
	document.querySelectorAll('.reward-effect-select').forEach(select => {
		const reward = rewards.find(r => r.id === select.dataset.rewardId);
		if (reward && reward.mapped_effect) {
			select.value = reward.mapped_effect;
		}
	});
}

async function saveRewardMapping(rewardId, btn) {
	const select = document.querySelector(`select[data-reward-id="${rewardId}"]`);
	const effectId = select.value;

	// Update local config
	const rewardMappings = { ...config.reward_mappings };
	if (effectId) {
		rewardMappings[rewardId] = { effect_id: effectId, enabled: true };
	} else {
		delete rewardMappings[rewardId];
	}

	// Also collect existing manual mappings
	document.querySelectorAll('.mapping-row').forEach(row => {
		const rid = row.querySelector('.mapping-reward').value.trim();
		const eid = row.querySelector('.mapping-effect').value.trim();
		const enabled = row.querySelector('.mapping-enabled').checked;
		if (rid && eid) rewardMappings[rid] = { effect_id: eid, enabled };
	});

	await fetch(API + '/api/mappings', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ reward_mappings: rewardMappings })
	});

	btn.textContent = 'Saved!';
	setTimeout(() => { btn.textContent = 'Save'; }, 1500);

	// Reload config
	loadConfig();
}

// --- Manual Reward Mappings ---
function renderMappings() {
	const container = document.getElementById('mappings-list');
	const mappings = config.reward_mappings || {};
	container.innerHTML = Object.entries(mappings).map(([rewardId, mapping]) => `
		<div class="mapping-row" data-reward="${rewardId}">
			<input type="text" value="${rewardId}" placeholder="Reward ID" class="mapping-reward">
			<span>&rarr;</span>
			<input type="text" value="${mapping.effect_id}" placeholder="Effect ID" class="mapping-effect">
			<label class="toggle">
				<input type="checkbox" ${mapping.enabled !== false ? 'checked' : ''} class="mapping-enabled">
				<span class="slider"></span>
			</label>
			<button class="btn btn-sm btn-danger" onclick="this.parentElement.remove()">X</button>
		</div>
	`).join('');
}

document.getElementById('add-mapping').addEventListener('click', () => {
	const container = document.getElementById('mappings-list');
	const row = document.createElement('div');
	row.className = 'mapping-row';
	row.innerHTML = `
		<input type="text" placeholder="Reward ID" class="mapping-reward">
		<span>&rarr;</span>
		<input type="text" placeholder="Effect ID" class="mapping-effect">
		<label class="toggle">
			<input type="checkbox" checked class="mapping-enabled">
			<span class="slider"></span>
		</label>
		<button class="btn btn-sm btn-danger" onclick="this.parentElement.remove()">X</button>
	`;
	container.appendChild(row);
});

// --- Bit Tiers ---
function renderBitTiers() {
	const container = document.getElementById('bit-tiers-list');
	const tiers = config.bit_tiers || [];
	container.innerHTML = tiers.map((tier, i) => `
		<div class="tier-row">
			<input type="number" value="${tier.min_bits}" min="1" placeholder="Min bits" class="tier-bits" style="width:100px">
			<span>bits &rarr;</span>
			<input type="text" value="${tier.effect_id}" placeholder="Effect ID" class="tier-effect">
			<label class="toggle">
				<input type="checkbox" ${tier.enabled !== false ? 'checked' : ''} class="tier-enabled">
				<span class="slider"></span>
			</label>
			<button class="btn btn-sm btn-danger" onclick="this.parentElement.remove()">X</button>
		</div>
	`).join('');
}

document.getElementById('add-bit-tier').addEventListener('click', () => {
	const container = document.getElementById('bit-tiers-list');
	const row = document.createElement('div');
	row.className = 'tier-row';
	row.innerHTML = `
		<input type="number" value="100" min="1" placeholder="Min bits" class="tier-bits" style="width:100px">
		<span>bits &rarr;</span>
		<input type="text" placeholder="Effect ID" class="tier-effect">
		<label class="toggle">
			<input type="checkbox" checked class="tier-enabled">
			<span class="slider"></span>
		</label>
		<button class="btn btn-sm btn-danger" onclick="this.parentElement.remove()">X</button>
	`;
	container.appendChild(row);
});

// Global save function for manual mappings + bit tiers
window.saveMappings = async function() {
	const rewardMappings = {};
	document.querySelectorAll('.mapping-row').forEach(row => {
		const rewardId = row.querySelector('.mapping-reward').value.trim();
		const effectId = row.querySelector('.mapping-effect').value.trim();
		const enabled = row.querySelector('.mapping-enabled').checked;
		if (rewardId && effectId) {
			rewardMappings[rewardId] = { effect_id: effectId, enabled };
		}
	});

	const bitTiers = [];
	document.querySelectorAll('.tier-row').forEach(row => {
		const minBits = parseInt(row.querySelector('.tier-bits').value);
		const effectId = row.querySelector('.tier-effect').value.trim();
		const enabled = row.querySelector('.tier-enabled').checked;
		if (effectId && minBits > 0) {
			bitTiers.push({ min_bits: minBits, effect_id: effectId, enabled });
		}
	});

	await fetch(API + '/api/mappings', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ reward_mappings: rewardMappings, bit_tiers: bitTiers })
	});

	alert('Mappings saved');
	loadConfig();
};

init();
