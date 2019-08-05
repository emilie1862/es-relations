import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import java.lang.reflect.Type

//Custom Serializer to pair down the Serialized kNote object when it is used
//as part of the ObjectKnotes in the Relationship object. The relationships
//array in a kNote cannot be serialized in that context since it would create a
//circular dependency
class ObjectKnoteSerializer : JsonSerializer<List<Knote>>, JsonDeserializer<List<Knote>> {

    override fun serialize(src: List<Knote>?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        val arr = JsonArray()

        src?.forEach {
            val obj = JsonObject()

            obj.addProperty("id", it.id)
            obj.addProperty("type", it.type)
            obj.addProperty("name", it.name)

            arr.add(obj)
        }

        return arr
    }

    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): List<Knote> {
        val list = mutableListOf<Knote>()

        json?.asJsonArray?.forEach {
            val knote = gson.fromJson(it,
                    Class.forName(it.asJsonObject.get("type").asString)) as Knote
            list.add(knote)
        }

        return list
    }
}

abstract class Knote(val id: String, val name: String) {
    val binIds: MutableSet<String> = mutableSetOf()
    val relationships: MutableSet<Relationship> = mutableSetOf()
    val type: String = this.javaClass.simpleName

    fun addBinId(binId: String) {
        binIds.add(binId)
    }

    fun addRelationship(relationship: Relationship) {
        addRelationship(relationship.type, relationship.objectKnotes)
    }

    fun addRelationship(relationshipType: String, objectKnotes: MutableCollection<Knote>) {
        val existingRelationship = relationships.firstOrNull{ it.type == relationshipType }

        //Check to see if the relationship type exists. If it does,
        //add the new related objectKnotes to that relationship object.
        //Otherwise add a new Relationship object to the relationships set
        if(existingRelationship != null) {
            relationships.remove(existingRelationship)

            //Only add the related object kNote if it doesn't already exist in the objectKnotes array
            objectKnotes.forEach outer@ {newKnote ->
                existingRelationship.objectKnotes.forEach {existingKnote ->
                    if(newKnote.id == existingKnote.id) {
                        return@outer
                    }
                }

                existingRelationship.objectKnotes.add(newKnote)
            }

            relationships.add(existingRelationship)
        } else {
            relationships.add(Relationship(relationshipType, objectKnotes))
        }
    }
}

class Relationship(val type: String,
                   @JsonAdapter(ObjectKnoteSerializer::class)
                   val objectKnotes: MutableCollection<Knote>)

class Place(id: String, name: String) : Knote(id, name)
class Person(id: String, name: String) : Knote(id, name)
class Event(id: String, name: String) : Knote(id, name)
