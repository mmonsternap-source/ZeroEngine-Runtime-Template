// ==================== ZeroEngine 节点默认属性标准化与工程模板库 ====================

function normalizeNode(n) {
  if (!Array.isArray(n.clips)) n.clips = [];
  if (!Array.isArray(n.proClips)) n.proClips = [];
  if (!Array.isArray(n.fsmTransitions)) n.fsmTransitions = [];
  if (!Array.isArray(n.tiles)) n.tiles = [];
  if (n.parallaxX === undefined) n.parallaxX = 1;
  if (n.parallaxY === undefined) n.parallaxY = 1;
  if (!n.slopeType) n.slopeType = 'none';
  if (!n.bodyMode) n.bodyMode = 'kinematic';
  if (n.gravity === undefined) n.gravity = 2200;
  if (n.fallMultiplier === undefined) n.fallMultiplier = 1.55;
  if (n.maxFallSpeed === undefined) n.maxFallSpeed = 1450;
  if (n.bounciness === undefined) n.bounciness = 0.5;
  if (n.drag === undefined) n.drag = 0;
  if (n.maxJumps === undefined) n.maxJumps = 2;
  if (n.isHitbox === undefined) n.isHitbox = false;
  if (n.isHurtbox === undefined) n.isHurtbox = false;
  if (n.damage === undefined) n.damage = 10;
  if (n.colOffsetX === undefined) n.colOffsetX = 0;
  if (n.colOffsetY === undefined) n.colOffsetY = 0;
  if (n.colWidth === undefined) n.colWidth = 0;
  if (n.colHeight === undefined) n.colHeight = 0;
  if (n.colLayer === undefined) n.colLayer = 1;
  if (n.colMask === undefined) n.colMask = 65535;
  if (n.persistent === undefined) n.persistent = false;
  if (n.scriptFile === undefined) n.scriptFile = '';
  if (n.sliceLeft === undefined) n.sliceLeft = 0;
  if (n.sliceRight === undefined) n.sliceRight = 0;
  if (n.sliceTop === undefined) n.sliceTop = 0;
  if (n.sliceBottom === undefined) n.sliceBottom = 0;
  if (n.clipChildren === undefined) n.clipChildren = false;
  if (n.clipX === undefined) n.clipX = 0;
  if (n.clipY === undefined) n.clipY = 0;
  if (n.clipW === undefined) n.clipW = 0;
  if (n.clipH === undefined) n.clipH = 0;
  return n;
}

function defaultNodes() {
  return [
    normalizeNode({
      name: "GroundFloor", nodeType: "Sprite2D", layer: "World", parent: "", tag: "ground",
      x: 540, y: 960, z: -2, width: 2200, height: 2200, rotation: 0, tiltX: 0, billboard: false,
      scaleX: 1, scaleY: 1, zIndex: -10, texture: "", r: 0.16, g: 0.20, b: 0.28, a: 1
    }),
    normalizeNode({
      name: "DungeonWalls", nodeType: "TileMap", layer: "World", parent: "", tag: "wall",
      x: 0, y: 0, z: 0, width: 90, height: 90, zIndex: 0, r: 0.36, g: 0.44, b: 0.56, a: 1,
      tileSize: 90,
      tiles: [
        { gx: 2, gy: 6, frame: 0 }, { gx: 3, gy: 6, frame: 0 }, { gx: 4, gy: 6, frame: 0 },
        { gx: 2, gy: 7, frame: 0 }, { gx: 2, gy: 8, frame: 0 }
      ],
      collider: true
    }),
    normalizeNode({
      name: "Hero", nodeType: "Sprite2D", layer: "World", parent: "", tag: "player",
      x: 540, y: 960, z: 0, width: 120, height: 150, billboard: true,
      zIndex: 1, r: 0.25, g: 0.82, b: 0.98, a: 1,
      shadowCaster: true, collider: true, bodyMode: "kinematic",
      defaultClip: "idle",
      clips: [
        { name: "idle", start: 0, end: 0, fps: 8, loop: true, eventsStr: "" },
        { name: "run", start: 0, end: 0, fps: 12, loop: true, eventsStr: "" }
      ],
      script: `var speed = 460;
function onReady() {
  Engine.setCameraMode("2.5D");
  Engine.setCameraTarget(self);
  self.play("idle");
  Engine.log("ZeroEngine v1.4 P7 动画引擎已就绪");
}
function onProcess(dt) {
  if (Input.isTouching && Input.screenY < 1550) {
    var dx = Input.worldX - self.x;
    var dy = Input.worldY - self.y;
    var dist = Math.hypot(dx, dy);
    if (dist > 15) {
      self.vx = (dx / dist) * speed;
      self.vy = (dy / dist) * speed;
      self.scaleX = dx < 0 ? -1 : 1;
      self.play("run");
    } else {
      self.vx = 0; self.vy = 0;
      self.play("idle");
    }
  } else {
    self.vx = 0; self.vy = 0;
    self.play("idle");
  }
  Engine.moveAndSlide(self, dt);
}
function onTriggerEnter(other) {
  if (other.tag === "coin") {
    Engine.playTone(980, 90);
    self.flash(0.14);
    Engine.tween(self, "scaleX", 1.25, 0.12, "easeOut");
    Engine.destroy(other);
  }
}`
    }),
    normalizeNode({
      name: "TorchLight", nodeType: "PointLight", layer: "World", parent: "Hero", tag: "light",
      x: 0, y: 0, z: 60, width: 60, height: 60, zIndex: 5, r: 1.0, g: 0.72, b: 0.28, a: 1,
      lightRadius: 580, lightIntensity: 1.8
    }),
    normalizeNode({
      name: "EmberParticles", nodeType: "ParticleEmitter", layer: "World", parent: "TorchLight", tag: "fx",
      x: 0, y: 0, z: 20, width: 40, height: 40, zIndex: 6, r: 1.0, g: 0.65, b: 0.18, a: 0.95,
      emitRate: 28, particleLife: 1.0, particleSpeed: 130, particleSize: 16, particleGravity: -90
    }),
    normalizeNode({
      name: "GoldCoin", nodeType: "Sprite2D", layer: "World", parent: "", tag: "coin",
      x: 780, y: 780, z: 0, width: 70, height: 70, billboard: true,
      zIndex: 1, r: 1.0, g: 0.84, b: 0.12, a: 1, collider: true, isTrigger: true
    }),
    normalizeNode({
      name: "ActionBtn", nodeType: "Sprite2D", layer: "UI", parent: "", tag: "ui_btn",
      x: 880, y: 1680, z: 0, width: 170, height: 170, zIndex: 100, r: 0.95, g: 0.32, b: 0.36, a: 0.85,
      script: `function onInput(ev) {
  if (ev.type === "down" && Math.abs(ev.screenX - self.x) < 95 && Math.abs(ev.screenY - self.y) < 95) {
    Engine.shake(16, 0.15);
    Engine.playTone(680, 70);
  }
}`
    })
  ];
}

function platformerTemplateNodes() {
  return [
    normalizeNode({
      name: "FarMountains", nodeType: "Sprite2D", layer: "World", tag: "bg",
      x: 540, y: 760, z: -20, width: 3200, height: 1400, parallaxX: 0.3, parallaxY: 0.5,
      r: 0.09, g: 0.13, b: 0.22, a: 1, zIndex: -20
    }),
    normalizeNode({
      name: "MainFloor", nodeType: "Sprite2D", layer: "World", tag: "ground",
      x: 900, y: 1260, z: 0, width: 3000, height: 120,
      r: 0.24, g: 0.30, b: 0.42, a: 1, collider: true
    }),
    normalizeNode({
      name: "SlopeRamp", nodeType: "Sprite2D", layer: "World", tag: "ground",
      x: 720, y: 1110, z: 0, width: 320, height: 180,
      r: 0.28, g: 0.42, b: 0.54, a: 1, collider: true, slopeType: "left_up"
    }),
    normalizeNode({
      name: "UpperDeck", nodeType: "Sprite2D", layer: "World", tag: "ground",
      x: 1120, y: 1110, z: 0, width: 480, height: 180,
      r: 0.24, g: 0.30, b: 0.42, a: 1, collider: true
    }),
    normalizeNode({
      name: "GrappleAnchor", nodeType: "Sprite2D", layer: "World", tag: "anchor",
      x: 920, y: 680, z: 0, width: 60, height: 60,
      r: 0.98, g: 0.82, b: 0.22, a: 1, collider: true
    }),
    normalizeNode({
      name: "TrainingDummy", nodeType: "Sprite2D", layer: "World", tag: "enemy",
      x: 1220, y: 945, z: 0, width: 96, height: 150,
      r: 0.92, g: 0.28, b: 0.36, a: 1, collider: true, isHurtbox: true,
      script: `var hp = 100;
function onHurt(attacker, dmg) {
  hp -= dmg;
  self.flash(0.14);
  Engine.playTone(320, 80);
  if (hp <= 0) {
    Engine.shake(22, 0.2);
    Engine.destroy(self);
  }
}`
    }),
    normalizeNode({
      name: "Knight", nodeType: "Sprite2D", layer: "World", tag: "player",
      x: 360, y: 1100, z: 0, width: 96, height: 140,
      r: 0.95, g: 0.96, b: 1.0, a: 1, collider: true, bodyMode: "platformer",
      gravity: 2300, fallMultiplier: 1.65, maxJumps: 2,
      script: `var moveDir = 0;
var slashTimer = 0;
function onReady() {
  Engine.setCameraMode("2D");
  Engine.setCameraTarget(self);
  Engine.setCameraLookAhead(140, -60);
  var saved = Engine.loadDisk("bench_save");
  if (saved) Engine.log("读取长椅存档成功: " + JSON.stringify(saved));
  Engine.on("move", function(dir) { moveDir = dir; });
  Engine.on("jump", function() {
    if (self.jump(960)) Engine.playTone(540, 60);
  });
  Engine.on("slash", function() {
    slashTimer = 0.14;
    var blade = Engine.findNode("SlashHitbox");
    if (blade) {
      blade.visible = true;
      blade.isHitbox = true;
      blade.x = self.scaleX >= 0 ? 110 : -110;
    }
    Engine.playTone(760, 50);
  });
  Engine.on("grapple", function() {
    var dirX = self.scaleX >= 0 ? 1 : -1;
    var ray = Engine.raycast(self.x, self.y, self.x + dirX * 650, self.y - 520, "player");
    if (ray.hit && ray.node && ray.node.tag === "anchor") {
      Engine.playTone(1120, 80);
      self.bounce(dirX * 540, -960);
      Engine.saveDisk("bench_save", { x: self.x, y: self.y, hp: 5 });
    }
  });
}
function onProcess(dt) {
  if (Math.abs(moveDir) > 0) {
    self.vx = moveDir * 480;
    self.scaleX = moveDir > 0 ? 1 : -1;
  } else if (self.isGrounded) {
    self.vx = 0;
  }
  if (slashTimer > 0) {
    slashTimer -= dt;
    if (slashTimer <= 0) {
      var blade = Engine.findNode("SlashHitbox");
      if (blade) { blade.visible = false; blade.isHitbox = false; }
    }
  }
  Engine.moveAndSlide(self, dt);
}`
    }),
    normalizeNode({
      name: "SlashHitbox", nodeType: "Sprite2D", layer: "World", parent: "Knight", tag: "weapon",
      x: 110, y: 0, z: 2, width: 130, height: 90, visible: false,
      r: 0.38, g: 0.92, b: 1.0, a: 0.85, isHitbox: false, damage: 25,
      script: `function onHit(target) {
  Engine.hitstop(0.055);
  Engine.shake(14, 0.12);
  var knight = Engine.findNode("Knight");
  if (knight && !knight.isGrounded) knight.bounce(null, -780);
}`
    }),
    normalizeNode({
      name: "BtnLeft", nodeType: "Sprite2D", layer: "UI", x: 140, y: 1680, width: 130, height: 130,
      r: 0.3, g: 0.5, b: 0.8, a: 0.75, zIndex: 100,
      script: `function onInput(ev) {
  if (Math.abs(ev.screenX - self.x) < 80 && Math.abs(ev.screenY - self.y) < 80)
    Engine.emit("move", ev.type === "up" ? 0 : -1);
}`
    }),
    normalizeNode({
      name: "BtnRight", nodeType: "Sprite2D", layer: "UI", x: 300, y: 1680, width: 130, height: 130,
      r: 0.3, g: 0.5, b: 0.8, a: 0.75, zIndex: 100,
      script: `function onInput(ev) {
  if (Math.abs(ev.screenX - self.x) < 80 && Math.abs(ev.screenY - self.y) < 80)
    Engine.emit("move", ev.type === "up" ? 0 : 1);
}`
    }),
    normalizeNode({
      name: "BtnGrapple", nodeType: "Sprite2D", layer: "UI", x: 580, y: 1680, width: 130, height: 130,
      r: 0.95, g: 0.75, b: 0.2, a: 0.8, zIndex: 100,
      script: `function onInput(ev) {
  if (ev.type === "down" && Math.abs(ev.screenX - self.x) < 80 && Math.abs(ev.screenY - self.y) < 80)
    Engine.emit("grapple", true);
}`
    }),
    normalizeNode({
      name: "BtnJump", nodeType: "Sprite2D", layer: "UI", x: 750, y: 1680, width: 130, height: 130,
      r: 0.2, g: 0.85, b: 0.55, a: 0.8, zIndex: 100,
      script: `function onInput(ev) {
  if (ev.type === "down" && Math.abs(ev.screenX - self.x) < 80 && Math.abs(ev.screenY - self.y) < 80)
    Engine.emit("jump", true);
}`
    }),
    normalizeNode({
      name: "BtnAttack", nodeType: "Sprite2D", layer: "UI", x: 920, y: 1680, width: 130, height: 130,
      r: 0.95, g: 0.32, b: 0.36, a: 0.85, zIndex: 100,
      script: `function onInput(ev) {
  if (ev.type === "down" && Math.abs(ev.screenX - self.x) < 80 && Math.abs(ev.screenY - self.y) < 80)
    Engine.emit("slash", true);
}`
    })
  ];
}
