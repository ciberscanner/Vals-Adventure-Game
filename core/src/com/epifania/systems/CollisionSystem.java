package com.epifania.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.EntitySystem;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.Gdx;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.epifania.components.*;

public class CollisionSystem extends EntitySystem implements ContactListener {

	private static final String tag = "Collision System";
	private static final String NONE = "NONE";
	public boolean action;
	public Button actionButton;
	public Button lockedButton;
	private Vector3 tmp = new Vector3();
	private Entity valEntity, otherEntity;

	private ComponentMapper<BoundsComponent> bm;
	private ComponentMapper<StateComponent> sm;
	private ComponentMapper<TransformComponent> tm;
	
	private ImmutableArray<Entity> vals;
	private ImmutableArray<Entity> coins;
	private ImmutableArray<Entity> switches;
	private ImmutableArray<Entity> checkpoints;
	private ImmutableArray<Entity> deathZones;
	private ImmutableArray<Entity> collectables;
	private ImmutableArray<Entity> actionables;
	private ImmutableArray<Entity> ladders;
	private ImmutableArray<Entity> packs;
	private ImmutableArray<Entity> trunks;

	public interface CollisionListener {
		void pickCoin ();
		void die();
		void checkpointReached(int number);
		void removeEntity(Entity entity);
		void pickObject(TextureRegion region,String key);
		void usedObject(String key);
		void pickPack(String content, int amount);
	}
	
	private CollisionListener listener;
	private Engine engine; 
	
	public CollisionSystem(CollisionListener listener){
		this.listener = listener;
		bm = ComponentMapper.getFor(BoundsComponent.class);
		sm = ComponentMapper.getFor(StateComponent.class);
		tm = ComponentMapper.getFor(TransformComponent.class);
	}
	
	@Override
	public void addedToEngine(Engine engine) {
		this.engine = engine;
		vals = this.engine.getEntitiesFor(Family.all(Val_Component.class,TransformComponent.class,BoundsComponent.class,StateComponent.class).get());
		coins = this.engine.getEntitiesFor(Family.all(CoinComponent.class,TransformComponent.class,BoundsComponent.class).get());
		switches = this.engine.getEntitiesFor(Family.all(SwitchComponent.class,AnimatedTileComponent.class,BoundsComponent.class).get());
		checkpoints = this.engine.getEntitiesFor(Family.all(CheckpointComponent.class,BoundsComponent.class,TransformComponent.class).get());
		deathZones = this.engine.getEntitiesFor(Family.all(DeathZoneComponent.class,BoundsComponent.class).get());
		collectables = this.engine.getEntitiesFor(Family.all(CollectableComponent.class,BoundsComponent.class,TextureComponent.class).get());
		actionables = this.engine.getEntitiesFor(Family.all(ActionableComponent.class,BoundsComponent.class).get());
		ladders = this.engine.getEntitiesFor(Family.all(LadderComponent.class,BoundsComponent.class).get());
		packs = this.engine.getEntitiesFor(Family.all(PackComponent.class,BoundsComponent.class).get());
		trunks = this.engine.getEntitiesFor(Family.all(TrunkComponent.class,BoundsComponent.class).get());
	}

	@Override
	public void update(float deltaTime) {
		for(Entity val : vals){
			BoundsComponent valBounds = bm.get(val);

			for(Entity coin : coins){
				if(coin.flags!=val.flags)continue;
				BoundsComponent coinBounds = bm.get(coin);
				if(coinBounds.bounds.overlaps(valBounds.bounds)){
					engine.removeEntity(coin);
					listener.pickCoin();
				}
			}

			boolean canClimb = false;
			for(Entity ladder : ladders){
				if(ladder.flags!=val.flags)continue;
				BoundsComponent boundsComponent = bm.get(ladder);
				if(boundsComponent.bounds.overlaps(valBounds.bounds)){
					canClimb=true;
				}
			}
			if(!canClimb) {
				if(engine.getSystem(Val_System.class).climbing){
					engine.getSystem(Val_System.class).climbing=false;
					engine.getSystem(Val_System.class).setState(val,Val_Component.WALKR);
				}
			}
			engine.getSystem(Val_System.class).canClimb = canClimb;

			boolean isOnSwitch = false;
			boolean isOnSwitchLocked = false;

			for(Entity sw : switches){
				if(sw.flags!=val.flags)continue;
				BoundsComponent boundsComponent = bm.get(sw);
				if(boundsComponent.bounds.overlaps(valBounds.bounds)){
					isOnSwitch = true;
					setDialogPosition(boundsComponent.bounds);
					if(action) {
						SwitchSystem switchSystem = engine.getSystem(SwitchSystem.class);
						switchSystem.actionate(sw);
						action = false;
					}
				}
			}
			setActionButtonVisibility(isOnSwitch);

			for(Entity entity : checkpoints){
				if(entity.flags!=val.flags)continue;
				BoundsComponent boundsComponent = bm.get(entity);
				CheckpointComponent checkpointComponent = entity.getComponent(CheckpointComponent.class);
				if(boundsComponent.bounds.overlaps(valBounds.bounds)){
					listener.checkpointReached(checkpointComponent.number);
				}
			}
			for(Entity entity : deathZones){
				if(entity.flags!=val.flags)continue;
				Val_System vs = this.getEngine().getSystem(Val_System.class);
				BoundsComponent boundsComponent = bm.get(entity);
				if(boundsComponent.bounds.overlaps(valBounds.bounds)){
					vs.deathCollision(val);
					listener.die();
				}
			}

			for(Entity entity : collectables){
				if(entity.flags!=val.flags)continue;
				if(!entity.getComponent(CollectableComponent.class).free)continue;
				BoundsComponent cb = bm.get(entity);
				if(valBounds.bounds.overlaps(cb.bounds)){
					isOnSwitch = true;
					setDialogPosition(cb.bounds);
					if(action) {
						listener.pickObject(entity.getComponent(TextureComponent.class).region,entity.getComponent(CollectableComponent.class).key);
						listener.removeEntity(entity);
						val.getComponent(Val_Component.class).objects.add(entity);
						String conversationKey = entity.getComponent(CollectableComponent.class).conversationKey;
						if(conversationKey != null){
							val.getComponent(Val_Component.class).conversationKeys.add(conversationKey);
//							val.getComponent(ConversationComponent.class).conditions.add(conversationKey);
						}
						action = false;
					}
				}
			}

			for(Entity entity : packs){
				if(entity.flags!=val.flags)continue;
				Rectangle otherBounds = bm.get(entity).bounds;
				if(valBounds.bounds.overlaps(otherBounds)){
					isOnSwitch = true;
					setDialogPosition(otherBounds);
					if(action) {
						PackComponent packComponent= entity.getComponent(PackComponent.class);
						listener.pickPack(packComponent.content,packComponent.amount);
						listener.removeEntity(entity);
					}
				}
			}

			for(Entity entity : trunks){
				if(entity.flags!=val.flags)continue;
				Rectangle otherBounds = bm.get(entity).bounds;
				TrunkComponent trunkComponent= entity.getComponent(TrunkComponent.class);
				if(valBounds.bounds.overlaps(otherBounds) && !trunkComponent.open){
					isOnSwitch = true;
					setDialogPosition(otherBounds);
					if(action) {
						listener.pickPack(trunkComponent.content,trunkComponent.amount);
						int tileID = TrunkComponent.EMPTY;
						trunkComponent.open = true;
						if(trunkComponent.content.equals("coins")){
							tileID = TrunkComponent.OPEN;
						}
						engine.getSystem(TiledMapSystem.class).setTile(entity,tileID);
					}
				}
			}

			//Prevent use two doors in different layer using break to label1
			label1: for(Entity entity : actionables){
				if(entity.flags!=val.flags)continue;
				Rectangle bounds = bm.get(entity).bounds;
				if(valBounds.bounds.overlaps(bounds)){
					if(entity.getComponent(ActionableComponent.class).key.equals(NONE)) {
						isOnSwitch = true;
						setDialogPosition(bounds);
						if (action) {
							entity.getComponent(ActionableComponent.class).actionable.action();
							break label1;
						}
					}else{
						for (Entity object : val.getComponent(Val_Component.class).objects) {
							if (object.getComponent(CollectableComponent.class).key.equals(entity.getComponent(ActionableComponent.class).key)) {
								isOnSwitch = true;
								setDialogPosition(bounds);
								if (action) {
									listener.usedObject(object.getComponent(CollectableComponent.class).key);
									entity.getComponent(ActionableComponent.class).key = NONE;
									entity.getComponent(ActionableComponent.class).actionable.action();
									val.getComponent(Val_Component.class).objects.removeValue(object, true);
									break label1;
								}
							}
						}
						if (!isOnSwitch) {
							isOnSwitchLocked = true;
							setDialogPosition(bounds);
							if(action){
								Gdx.app.debug(tag,"show thoughts");
								engine.getSystem(Val_System.class).showThoughts(val,
										entity.getComponent(ActionableComponent.class).key);
							}
						}
					}
				}
			}

			//Reset button and action
			if(action)action = false;
			setButtonVisibility(actionButton,isOnSwitch);
			setButtonVisibility(lockedButton,isOnSwitchLocked);
		}
	}

	private void setDialogPosition(Rectangle bounds){
		float offsetY = 0.0f;
		Camera camera = engine.getSystem(RenderingSystem.class).getCamera();
		tmp.set(bounds.getX()+bounds.getWidth()*0.5f,bounds.getY()+bounds.getHeight()+offsetY,0);
		camera.project(tmp);
		tmp.y = Gdx.graphics.getHeight() - tmp.y;
		actionButton.getStage().getCamera().unproject(tmp);
		actionButton.setPosition(tmp.x-actionButton.getWidth()*0.5f,tmp.y);
		lockedButton.setPosition(tmp.x - lockedButton.getWidth()*0.5f,tmp.y);
	}

	private void setActionButtonVisibility(boolean isOnSwitch){
		if(!isOnSwitch) {
			if (actionButton.isVisible())
				actionButton.setVisible(false);
		}
		else {
			if(!actionButton.isVisible())
				actionButton.setVisible(true);
		}
//			Gdx.app.debug(getClass().getName(),"actionButton.position = "+actionButton.getX()+","+actionButton.getY());
	}

	private void setButtonVisibility(Button button,boolean isOnSwitch){
		if(!isOnSwitch) {
			if (button.isVisible())
				button.setVisible(false);
		}
		else {
			if(!button.isVisible())
				button.setVisible(true);
		}
	}

	//***********************************BOX2D*****************************************
	@Override
	public void beginContact(Contact contact) {
		valEntity = null; otherEntity = null;
		getFeetCollisionEntity(contact.getFixtureA(),contact.getFixtureB());
		if(valEntity!=null && otherEntity!=null) {

				if (otherEntity.getComponent(GroundComponent.class) != null) {
					addFeetCollide(valEntity);
				}
				if (otherEntity.getComponent(BoxComponent.class) != null) {
					addFeetCollide(valEntity);
				}
				if (Family.all(BridgeComponent.class).get().matches(otherEntity)) {
					addFeetCollide(valEntity);
					bridgeCollision();
				}
				if (otherEntity.getComponent(PlatformComponent.class) != null) {
					addFeetCollide(valEntity);
				}
		}

		valEntity = null; otherEntity = null;
		getBodyCollisionEntity(contact.getFixtureA(),contact.getFixtureB());
		if(valEntity==null || otherEntity==null) return;
			if (otherEntity.getComponent(GroundComponent.class) != null) {
				addBodyCollide(valEntity);
			}
			if (otherEntity.getComponent(BoxComponent.class) != null) {
				addBodyCollide(valEntity);
			}
			if (Family.all(BridgeComponent.class).get().matches(otherEntity)) {
				addBodyCollide(valEntity);
			}
			if (otherEntity.getComponent(PlatformComponent.class) != null) {
				addBodyCollide(valEntity);
				platformCollision();
			}

		if(otherEntity.getComponent(SpringComponent.class)!=null){
			springCollision();
		}
		if(Family.all(ButtonComponent.class,ActionableComponent.class,BodyComponent.class).get().matches(otherEntity)){
			buttonCollision();
		}

	}

	@Override
	public void endContact(Contact contact) {
		valEntity =null;
		otherEntity = null;

		if(valEntity!=null && otherEntity!=null) {
			if (otherEntity.getComponent(GroundComponent.class) != null) {
				subBodyCollide(valEntity);
			}
			if (otherEntity.getComponent(BoxComponent.class) != null) {
				subBodyCollide(valEntity);
			}
			if (Family.all(BridgeComponent.class).get().matches(otherEntity)) {
				subBodyCollide(valEntity);
			}
			if (otherEntity.getComponent(PlatformComponent.class) != null) {
				subBodyCollide(valEntity);
			}
		}

		valEntity = null; otherEntity = null;
		getFeetCollisionEntity(contact.getFixtureA(),contact.getFixtureB());
		if(valEntity==null || otherEntity==null) return;

		if(otherEntity.getComponent(GroundComponent.class)!=null){
			subFeetCollide(valEntity);
		}
		if(otherEntity.getComponent(BoxComponent.class)!=null){
			subFeetCollide(valEntity);
		}
		if(otherEntity.getComponent(PlatformComponent.class)!=null){
			subFeetCollide(valEntity);
		}
		if(Family.all(BridgeComponent.class).get().matches(otherEntity)){
			subFeetCollide(valEntity);
			valEntity.getComponent(MovementComponent.class).bringerBody = null;
		}
	}

	@Override
	public void preSolve(Contact contact, Manifold oldManifold) {
		valEntity = null; otherEntity = null;
		getBodyCollisionEntity(contact.getFixtureA(),contact.getFixtureB());
		if(valEntity!=null && otherEntity!=null) {
			if (Family.one(BridgeComponent.class,GroundComponent.class,PlatformComponent.class).all(TransformComponent.class,BoundsComponent.class).get().matches(otherEntity)) {
				TransformComponent tv = valEntity.getComponent(TransformComponent.class);
				TransformComponent to = otherEntity.getComponent(TransformComponent.class);
				BoundsComponent bo = otherEntity.getComponent(BoundsComponent.class);

				float valBottom = tv.pos.y - Val_Component.HEIGHT*0.5f;
				float objectTop = to.pos.y + bo.bounds.getHeight()*0.5f;
				if(valBottom + TransformComponent.DELTA < objectTop){
					contact.setEnabled(false);
				}
			}
		}
	}

	@Override
	public void postSolve(Contact contact, ContactImpulse impulse) {

	}

	/**
	 * Gets the entity which Val feet collides with and store it in output Entities.
	 * All body.userData must be an Entity object
	 * @param fixA contact Fixture
	 * @param fixB contact fixture
	 */
	private void getFeetCollisionEntity(Fixture fixA, Fixture fixB){
		Object dataA = fixA.getUserData();
		Object dataB = fixB.getUserData();

		if(dataA != null){
			if(dataA.equals("feetFixture")){
				valEntity = (Entity) fixA.getBody().getUserData();
				otherEntity = (Entity) fixB.getBody().getUserData();
			}
		}
		if(dataB != null){
			if(dataB.equals("feetFixture")){
				valEntity = (Entity) fixB.getBody().getUserData();
				otherEntity = (Entity) fixA.getBody().getUserData();
			}
		}
	}

	/**
	 * Gets the entity which Val body collides with and store it in output Entities.
	 * All body.userData must be an Entity object
	 * @param fixA contact Fixture
	 * @param fixB contact fixture
	 */
	private void getBodyCollisionEntity(Fixture fixA, Fixture fixB){
		Object dataA = fixA.getUserData();
		Object dataB = fixB.getUserData();

		if(dataA != null){
			if(dataA.equals("bodyFixture")){
				valEntity = (Entity) fixA.getBody().getUserData();
				otherEntity = (Entity) fixB.getBody().getUserData();
			}
		}
		if(dataB != null){
			if(dataB.equals("bodyFixture")){
				valEntity = (Entity) fixB.getBody().getUserData();
				otherEntity = (Entity) fixA.getBody().getUserData();
			}
		}
	}

	private boolean checkIfCanJump(Entity val) {
		Val_Component component = val.getComponent(Val_Component.class);
		BodyComponent bodyComponent = val.getComponent(BodyComponent.class);
		MovementComponent movementComponent = val.getComponent(MovementComponent.class);
		if(component.feetCollide > 0 && component.bodyCollide > 0){
			if(movementComponent.bringerBody!=null){
				if(bodyComponent.body.getLinearVelocity().y - movementComponent.bringerBody.getLinearVelocity().y <= 0){
					this.getEngine().getSystem(Val_System.class).endJump();
				}
			}else{
				if(bodyComponent.body.getLinearVelocity().y <= 0){
					this.getEngine().getSystem(Val_System.class).endJump();
				}
			}
			return true;
		}
		return false;
	}
	private void addBodyCollide(Entity val){
		val.getComponent(Val_Component.class).bodyCollide++;
		checkIfCanJump(val);
	}
	private void addFeetCollide(Entity val){
		val.getComponent(Val_Component.class).feetCollide++;
		checkIfCanJump(val);
	}
	private void subBodyCollide(Entity val){
		val.getComponent(Val_Component.class).bodyCollide--;
		this.getEngine().getSystem(Val_System.class).canJump=checkIfCanJump(val);

	}
	private void subFeetCollide(Entity val){
		val.getComponent(Val_Component.class).feetCollide--;
		this.getEngine().getSystem(Val_System.class).canJump=checkIfCanJump(val);
	}
	private void springCollision(){
		Val_System valSystem = this.getEngine().getSystem(Val_System.class);
		SpringSystem springSystem = getEngine().getSystem(SpringSystem.class);

		if(valEntity.getComponent(BodyComponent.class).body.getLinearVelocity().y + SpringComponent.RESISTANCE <= 0) {
			valSystem.springCollision(valEntity);
			valSystem.canJump = false;
			springSystem.expandSpring(otherEntity);
		}else{
			valSystem.endJump();
		}
	}
	private void buttonCollision(){
		Val_System valSystem = this.getEngine().getSystem(Val_System.class);

		if(valEntity.getComponent(BodyComponent.class).body.getLinearVelocity().y + ButtonComponent.RESISTANCE <= 0) {
			ActionableComponent actionableComponent = otherEntity.getComponent(ActionableComponent.class);
			actionableComponent.actionable.action();
		}
		valSystem.endJump();
	}
	private void platformCollision(){
		engine.getSystem(PlatformSystem.class).characterCollision(otherEntity);
	}
	private void bridgeCollision(){
//		Val_System vs = this.getEngine().getSystem(Val_System.class);
//		vs.endJump();
		valEntity.getComponent(MovementComponent.class).bringerBody = otherEntity.getComponent(BodyComponent.class).body;
	}
}
