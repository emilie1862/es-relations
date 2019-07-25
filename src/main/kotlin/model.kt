import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonSerialize

//Annotations Used to generate the type field for all of the kNote types that extend
//the abstract Knote class
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes(value = [
    JsonSubTypes.Type(value = Place::class),
    JsonSubTypes.Type(value = Person::class),
    JsonSubTypes.Type(value = Event::class)])
abstract class Knote(val id: String, val name: String) {
    val binIds: MutableSet<String> = mutableSetOf()
    val relationships: MutableSet<Relationship> = mutableSetOf()

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
                   @JsonSerialize(contentUsing = ObjectKnoteSerializer::class)
                   val objectKnotes: MutableCollection<Knote>)

class Place(id: String, name: String) : Knote(id, name)
class Person(id: String, name: String) : Knote(id, name)
class Event(id: String, name: String) : Knote(id, name)
