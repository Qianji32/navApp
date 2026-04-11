import json

nodes = []
edges = []
rooms = []
connectors = []

# === STAIR NODES (base floor=1, spans 1-4) ===
stairs = [
    ("STAIR_NW", 3, 50),
    ("STAIR_N", 42, 52),
    ("STAIR_C", 42, 26),
    ("STAIR_NE", 82, 50),
]
for sid, sx, sy in stairs:
    nodes.append({"id": sid, "type": "stair", "x": sx, "y": sy, "floor": 1, "floors": [1,2,3,4]})
    connectors.append({"id": sid, "type": "stair", "node": sid, "floors": [1,2,3,4], "accessible": False})

# === CORRIDOR BACKBONE (per floor) ===
mc_pos = [(5,30),(18,30),(30,30),(42,30),(54,30),(65,30),(75,30)]
nc_pos = [(5,46),(18,46),(30,46),(42,46),(54,46),(65,46),(75,46)]
sc_pos = [(5,14),(18,14),(30,14),(42,14),(54,14),(65,14),(75,14)]
rc_pos = [(82,46),(82,38),(82,30),(82,22),(82,14)]
chain_d = [13,12,12,12,11,10]
rc_d = [8,8,8,8]

for f in [1,2,3,4]:
    p = f"F{f}_"
    for i,(x,y) in enumerate(mc_pos): nodes.append({"id":f"{p}MC{i}","type":"corridor","x":x,"y":y,"floor":f})
    for i,(x,y) in enumerate(nc_pos): nodes.append({"id":f"{p}NC{i}","type":"corridor","x":x,"y":y,"floor":f})
    for i,(x,y) in enumerate(sc_pos): nodes.append({"id":f"{p}SC{i}","type":"corridor","x":x,"y":y,"floor":f})
    for i,(x,y) in enumerate(rc_pos): nodes.append({"id":f"{p}RC{i}","type":"corridor","x":x,"y":y,"floor":f})
    
    for i in range(6):
        edges.append({"from":f"{p}MC{i}","to":f"{p}MC{i+1}","distance":chain_d[i],"accessible":True,"edge_type":"corridor"})
        edges.append({"from":f"{p}NC{i}","to":f"{p}NC{i+1}","distance":chain_d[i],"accessible":True,"edge_type":"corridor"})
        edges.append({"from":f"{p}SC{i}","to":f"{p}SC{i+1}","distance":chain_d[i],"accessible":True,"edge_type":"corridor"})
    edges.append({"from":f"{p}MC6","to":f"{p}RC2","distance":7,"accessible":True,"edge_type":"corridor"})
    edges.append({"from":f"{p}NC6","to":f"{p}RC0","distance":7,"accessible":True,"edge_type":"corridor"})
    edges.append({"from":f"{p}SC6","to":f"{p}RC4","distance":7,"accessible":True,"edge_type":"corridor"})
    for i in range(4):
        edges.append({"from":f"{p}RC{i}","to":f"{p}RC{i+1}","distance":rc_d[i],"accessible":True,"edge_type":"corridor"})
    for ci in [0,3,6]:
        edges.append({"from":f"{p}MC{ci}","to":f"{p}NC{ci}","distance":16,"accessible":True,"edge_type":"corridor"})
        edges.append({"from":f"{p}MC{ci}","to":f"{p}SC{ci}","distance":16,"accessible":True,"edge_type":"corridor"})
    
    stair_conn = [("NC0","STAIR_NW",4),("NC3","STAIR_N",6),("MC3","STAIR_C",4),("RC0","STAIR_NE",4)]
    for cs,sb,d in stair_conn:
        sid = sb if f==1 else f"{sb}#F{f}"
        edges.append({"from":f"{p}{cs}","to":sid,"distance":d,"accessible":False,"edge_type":"stair"})

# === FLOOR 1 - ENTRANCE ===
nodes.append({"id":"F1_ENTRANCE","type":"entrance","x":42,"y":0,"floor":1})
nodes.append({"id":"F1_EXIT_W","type":"entrance","x":0,"y":30,"floor":1})
nodes.append({"id":"F1_D_LOBBY","type":"room-door","x":42,"y":22,"floor":1,"room":"大厅/服务台"})
edges.append({"from":"F1_ENTRANCE","to":"F1_SC3","distance":14,"accessible":True,"edge_type":"door"})
edges.append({"from":"F1_D_LOBBY","to":"F1_MC3","distance":8,"accessible":True,"edge_type":"door"})
edges.append({"from":"F1_EXIT_W","to":"F1_MC0","distance":5,"accessible":True,"edge_type":"door"})
rooms.append({"id":"大厅/服务台","name":"大厅/服务台","door":"F1_D_LOBBY"})

# === FLOOR 2 ===
f2r = [
    ("F2_D_CLASSROOM_W","room-door",18,43,"教室(西)","F2_NC1",3),
    ("F2_D_BOOKSTORE","room-door",38,43,"书库","F2_NC3",5),
    ("F2_D_CLASSROOM_E","room-door",58,43,"教室(东)","F2_NC4",5),
    ("F2_D_NEWBOOK","room-door",44,17,"新书借阅室","F2_SC3",4),
    ("F2_D_WC_NW","room-door",30,27,"2F卫生间(西北)","F2_MC2",3),
    ("F2_D_WC_NE","room-door",36,27,"2F卫生间(西南)","F2_MC2",7),
    ("F2_D_WC_SW","room-door",58,27,"2F卫生间(东北)","F2_MC5",7),
    ("F2_D_WC_SE","room-door",64,27,"2F卫生间(东南)","F2_MC5",3),
]
for nid,ntype,nx,ny,name,corridor,dist in f2r:
    nodes.append({"id":nid,"type":ntype,"x":nx,"y":ny,"floor":2,"room":name})
    edges.append({"from":nid,"to":corridor,"distance":dist,"accessible":True,"edge_type":"door"})
    rooms.append({"id":name,"name":name,"door":nid})

# === FLOOR 3 ===
f3r = [
    ("F3_D_SCIENCE","room-door",18,43,"理学院","F3_NC1",3),
    ("F3_D_BOOKSTORE","room-door",38,43,"3F书库","F3_NC3",5),
    ("F3_D_HUMANITY","room-door",58,43,"人文学院","F3_NC4",5),
    ("F3_D_FOREIGN","room-door",78,44,"外文阅览室","F3_NC6",4),
    ("F3_D_PERIODICAL","room-door",44,17,"中文报刊室","F3_SC3",4),
    ("F3_D_WC_NW","room-door",30,27,"3F卫生间(西北)","F3_MC2",3),
    ("F3_D_WC_NE","room-door",36,27,"3F卫生间(西南)","F3_MC2",7),
    ("F3_D_WC_SW","room-door",58,27,"3F卫生间(东北)","F3_MC5",7),
    ("F3_D_WC_SE","room-door",64,27,"3F卫生间(东南)","F3_MC5",3),
]
for nid,ntype,nx,ny,name,corridor,dist in f3r:
    nodes.append({"id":nid,"type":ntype,"x":nx,"y":ny,"floor":3,"room":name})
    edges.append({"from":nid,"to":corridor,"distance":dist,"accessible":True,"edge_type":"door"})
    rooms.append({"id":name,"name":name,"door":nid})

# === FLOOR 4 ===
f4r = [
    ("F4_D_STUDY_W","room-door",18,43,"自习室(西)","F4_NC1",3),
    ("F4_D_BOOKSTORE","room-door",38,43,"4F书库","F4_NC3",5),
    ("F4_D_STUDY_E","room-door",58,43,"自习室(东)","F4_NC4",5),
    ("F4_D_EREAD","room-door",78,44,"电子阅览室","F4_NC6",4),
    ("F4_D_402","room-door",80,42,"402","F4_RC0",5),
    ("F4_D_403","room-door",84,42,"403","F4_RC0",5),
    ("F4_D_405","room-door",84,34,"405","F4_RC1",5),
    ("F4_D_407","room-door",84,20,"407","F4_RC3",5),
    ("F4_D_408","room-door",80,20,"408","F4_RC3",3),
    ("F4_D_DISC","room-door",22,17,"光盘库","F4_SC1",5),
    ("F4_D_TRAIN","room-door",44,17,"培训室","F4_SC3",4),
    ("F4_D_DIGITAL","room-door",72,17,"数字化特藏室","F4_SC5",5),
    ("F4_D_WC_NW","room-door",30,27,"4F卫生间(西北)","F4_MC2",3),
    ("F4_D_WC_NE","room-door",36,27,"4F卫生间(西南)","F4_MC2",7),
    ("F4_D_WC_SW","room-door",58,27,"4F卫生间(东北)","F4_MC5",7),
    ("F4_D_WC_SE","room-door",64,27,"4F卫生间(东南)","F4_MC5",3),
]
for nid,ntype,nx,ny,name,corridor,dist in f4r:
    nodes.append({"id":nid,"type":ntype,"x":nx,"y":ny,"floor":4,"room":name})
    edges.append({"from":nid,"to":corridor,"distance":dist,"accessible":True,"edge_type":"door"})
    rooms.append({"id":name,"name":name,"door":nid})

data = {
    "meta":{"name":"BUPT 图书馆 1-4层","units":"meters (approx)","version":"3.0-multifloor"},
    "nodes":nodes,"edges":edges,"rooms":rooms,"connectors":connectors
}

with open(r"c:\navApp\app\src\main\assets\map_data.json","w",encoding="utf-8") as f:
    json.dump(data,f,ensure_ascii=False,indent=2)

print(f"Done: {len(nodes)} nodes, {len(edges)} edges, {len(rooms)} rooms, {len(connectors)} connectors")
