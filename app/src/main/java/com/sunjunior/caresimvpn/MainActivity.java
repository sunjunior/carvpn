package com.sunjunior.caresimvpn;

import android.app.Activity;
import android.content.Intent;
import android.net.Network;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 42;

    private EditText host, port, user, pass, pkgs, subUrl;
    private TextView status;
    private Button toggle, importSub;
    private Spinner nodeList;
    private CheckBox autoSelect;

    private final List<NodeStore.Node> nodes = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private boolean filling = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        host = (EditText) findViewById(R.id.host);
        port = (EditText) findViewById(R.id.port);
        user = (EditText) findViewById(R.id.user);
        pass = (EditText) findViewById(R.id.pass);
        pkgs = (EditText) findViewById(R.id.pkgs);
        subUrl = (EditText) findViewById(R.id.subUrl);
        status = (TextView) findViewById(R.id.status);
        toggle = (Button) findViewById(R.id.toggle);
        importSub = (Button) findViewById(R.id.importSub);
        nodeList = (Spinner) findViewById(R.id.nodeList);
        autoSelect = (CheckBox) findViewById(R.id.autoSelect);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        nodeList.setAdapter(adapter);
        nodeList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (filling || position < 0 || position >= nodes.size()) return;
                NodeStore.setActive(MainActivity.this, position);
                fillFromNode(nodes.get(position));
                /* 人工选择 = 不再自动挑最快 */
                if (autoSelect.isChecked()) autoSelect.setChecked(false);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleVpn(); }
        });
        /* 白名单实时保存，无需单独的"保存配置"按钮 */
        pkgs.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (!filling) WhitelistStore.save(MainActivity.this, s.toString());
            }
        });
        importSub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { importSubscription(); }
        });
        autoSelect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (filling) return;
                NodeStore.setAuto(MainActivity.this, isChecked);
                if (isChecked) pickFastestAsync();
            }
        });
        findViewById(R.id.toBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveAll();
                moveTaskToBack(true);   /* 代理继续运行 */
            }
        });
        findViewById(R.id.exitApp).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitCompletely(); }
        });

        subUrl.setText(NodeStore.getSubUrl(this));
        filling = true;
        autoSelect.setChecked(NodeStore.isAuto(this));
        filling = false;
        reloadNodes(NodeStore.getActive(this));

        String wl = WhitelistStore.loadRaw(this);
        if (wl == null || wl.trim().isEmpty()) {
            wl = "com.tencent.qqmusiccar,com.tencent.qqlive.audiobox";
        }
        pkgs.setText(wl);
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /* ---------- 节点列表 ---------- */

    private void reloadNodes(int select) {
        nodes.clear();
        nodes.addAll(NodeStore.loadOrMigrate(this));
        adapter.clear();
        for (NodeStore.Node n : nodes) adapter.add(n.label());
        adapter.notifyDataSetChanged();

        filling = true;
        if (nodes.isEmpty()) {
            host.setText("");
            port.setText("");
            user.setText("");
            pass.setText("");
        } else {
            int idx = (select < 0 || select >= nodes.size()) ? 0 : select;
            fillFromNode(nodes.get(idx));
            selectProgrammatic(idx);
            NodeStore.setActive(this, idx);
        }
    }

    private void refreshAdapter(int select) {
        adapter.clear();
        for (NodeStore.Node n : nodes) adapter.add(n.label());
        adapter.notifyDataSetChanged();
        selectProgrammatic(select);
    }

    private void fillFromNode(NodeStore.Node n) {
        if (n == null) return;
        boolean prev = filling;
        filling = true;
        host.setText(n.host);
        port.setText(n.port > 0 ? String.valueOf(n.port) : "");
        user.setText(n.username);
        pass.setText(n.password);
        filling = prev;
    }

    /** 程序化切换下拉项：延长 filling 保护期，避免异步 onItemSelected 把"自动"取消。 */
    private void selectProgrammatic(final int idx) {
        if (idx < 0 || idx >= nodes.size()) return;
        filling = true;
        nodeList.setSelection(idx);
        new Handler().postDelayed(new Runnable() {
            @Override public void run() { filling = false; }
        }, 1000);
    }

    private void saveAll() {
        NodeConfig c = NodeConfig.fromFields(host.getText().toString(), port.getText().toString(),
                user.getText().toString(), pass.getText().toString());
        int sel = nodeList.getSelectedItemPosition();
        if (c.isComplete()) {
            NodeStore.Node n;
            if (sel >= 0 && sel < nodes.size()) {
                n = nodes.get(sel);
            } else {
                n = new NodeStore.Node();
                nodes.add(n);
                sel = nodes.size() - 1;
            }
            if (n.name == null || n.name.trim().isEmpty()) n.name = c.host + ":" + c.port;
            n.host = c.host;
            n.port = c.port;
            n.username = c.username;
            n.password = c.password;
            NodeStore.save(this, nodes);
            refreshAdapter(sel);
            NodeStore.setActive(this, sel);
        }
        WhitelistStore.save(this, pkgs.getText().toString());
        NodeStore.setAuto(this, autoSelect.isChecked());
        NodeStore.setSubUrl(this, subUrl.getText().toString().trim());
    }

    /* ---------- 订阅导入 ---------- */

    /** 出口网络：优先 WiFi，其次 eth0（见 {@link UpstreamNet}）。 */
    private Network upstreamNetwork() {
        return UpstreamNet.pick(this);
    }

    private void importSubscription() {
        final String url = subUrl.getText().toString().trim();
        if (url.isEmpty()) { toast("请先填写订阅链接"); return; }

        final Network net = upstreamNetwork();
        importSub.setEnabled(false);
        status.setText("正在导入订阅…");
        android.util.Log.i("carvpn", "import start");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String text = SubImporter.fetch(url, net, 12000, MainActivity.this);
                    final SubImporter.Parsed parsed = SubImporter.parseClash(text);
                    android.util.Log.i("carvpn", "import fetched " + text.length() + " bytes, http nodes=" + parsed.nodes.size());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            importSub.setEnabled(true);
                            if (parsed.nodes.isEmpty()) {
                                status.setText("停止");
                                toast("未解析到 http 节点（跳过其它类型 " + parsed.skippedOtherType + " 个）");
                                return;
                            }
                            nodes.clear();
                            nodes.addAll(parsed.nodes);
                            NodeStore.save(MainActivity.this, nodes);
                            NodeStore.setSubUrl(MainActivity.this, url);
                            NodeStore.setActive(MainActivity.this, 0);
                            refreshAdapter(0);
                            fillFromNode(nodes.get(0));
                            status.setText("已导入 " + nodes.size() + " 个节点");
                            toast("导入成功：" + nodes.size() + " 个 http 节点");
                            if (autoSelect.isChecked()) pickFastestAsync();
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.e("carvpn", "import failed", e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            importSub.setEnabled(true);
                            status.setText("停止");
                            toast("导入失败：" + e.getClass().getSimpleName());
                        }
                    });
                }
            }
        }, "sub-import").start();
    }

    /** 并行探活，选中时延最低的节点（TLS 握手 + CONNECT 200 双条件）。 */
    private void pickFastestAsync() {
        if (nodes.isEmpty()) { toast("请先导入订阅"); return; }
        final List<NodeStore.Node> snapshot = new ArrayList<>(nodes);
        NodeProbe.bindNetwork = upstreamNetwork();
        /* 首次安装、服务尚未启动时也要能探活 TLS 节点 */
        NodeProbe.appContext = getApplicationContext();
        status.setText("正在测速（" + snapshot.size() + " 个节点）…");

        new Thread(new Runnable() {
            @Override public void run() {
                final long[] lat = NodeProbe.probeAll(snapshot, 3000);
                final int idx = NodeStore.fastestIndex(lat);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (idx >= 0 && idx < nodes.size()) {
                            fillFromNode(nodes.get(idx));
                            selectProgrammatic(idx);
                            NodeStore.setActive(MainActivity.this, idx);
                            toast("最快：" + nodes.get(idx).label() + "（" + lat[idx] + "ms）");
                        } else {
                            toast("没有节点探活成功，保留当前选择");
                        }
                        refreshStatus();
                    }
                });
            }
        }, "probe-fastest").start();
    }

    /* ---------- 启动/停止 ---------- */

    private void toggleVpn() {
        saveAll();
        if (EsimVpnService.running) {
            startService(new Intent(this, EsimVpnService.class).setAction(EsimVpnService.ACTION_STOP));
            postRefresh(1000);
            return;
        }
        NodeConfig n = NodeConfig.fromFields(host.getText().toString(),
                port.getText().toString(), user.getText().toString(), pass.getText().toString());
        if (!n.isComplete()) { toast("请先填写节点地址与端口"); return; }
        if (WhitelistStore.normalize(pkgs.getText().toString()).isEmpty()) {
            toast("至少填写一个要代理的包名");
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, REQ_VPN);
        else startVpn();
    }

    private void startVpn() {
        status.setText("启动中…");
        startService(new Intent(this, EsimVpnService.class));
        postRefresh(1500);
        postRefresh(4000);
    }

    private void exitCompletely() {
        saveAll();
        startService(new Intent(this, EsimVpnService.class).setAction(EsimVpnService.ACTION_STOP));
        finishAndRemoveTask();
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 1200);
    }

    private void postRefresh(long delayMs) {
        new Handler().postDelayed(new Runnable() {
            @Override public void run() { refreshStatus(); }
        }, delayMs);
    }

    private void refreshStatus() {
        if (EsimVpnService.running) {
            String label = EsimVpnService.currentLabel;
            status.setText("运行中" + (label.isEmpty() ? "" : " · " + label));
            toggle.setText("停止代理");
            syncRunningNode(label);
        } else {
            status.setText("停止");
            toggle.setText("启动代理");
        }
    }

    /** 运行时把下拉框同步到实际使用的节点（含故障切换后的节点）。 */
    private void syncRunningNode(String label) {
        if (label == null || label.isEmpty()) return;
        for (int i = 0; i < nodes.size(); i++) {
            if (label.equals(nodes.get(i).label())) {
                if (nodeList.getSelectedItemPosition() != i) {
                    fillFromNode(nodes.get(i));
                    selectProgrammatic(i);
                    NodeStore.setActive(this, i);
                }
                return;
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        /* 离开界面即保存节点参数/订阅地址/自动开关，无需"保存"按钮 */
        saveAll();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_VPN) {
            if (res == RESULT_OK) { toast("已授权"); startVpn(); }
            else toast("未授权，无法启动");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
