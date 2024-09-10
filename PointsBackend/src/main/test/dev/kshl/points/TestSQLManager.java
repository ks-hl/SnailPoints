package dev.kshl.points;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestSQLManager {
    @Test
    public void testSQLManager() throws Exception {
        File databaseFile = new File("test/test2.db");
        databaseFile.delete();
        assert !databaseFile.exists();

        SQLManager sqlManager = new SQLManager(databaseFile);
        sqlManager.init();
        sqlManager.execute("DROP TABLE IF EXISTS points", 3000);
        sqlManager.close();
        sqlManager = new SQLManager(databaseFile);
        sqlManager.init();

        List<SQLManager.Person> people = new ArrayList<>();
        people.add(sqlManager.add(0, "person2"));
        people.add(sqlManager.add(0, "person3"));
        people.add(sqlManager.add(0, "person4"));

        List<SQLManager.Person> peopleRetrieved = sqlManager.getPeople(0);
        assertEquals(people.size(), peopleRetrieved.size());
        assertArrayEquals(new Integer[]{1, 2, 3}, peopleRetrieved.stream().map(SQLManager.Person::id).toArray());

        assert !sqlManager.setPriority(1, 1, true);
        assert !sqlManager.setPriority(1, 2, true);

        assert !sqlManager.setPriority(0, 1, true);
        assert !sqlManager.setPriority(0, 3, false);

        peopleRetrieved = sqlManager.getPeople(0);
        assertEquals(people.size(), peopleRetrieved.size());
        assertArrayEquals(new Integer[]{1, 2, 3}, peopleRetrieved.stream().map(SQLManager.Person::id).toArray());

        assert sqlManager.setPriority(0, 1, false);
        assert sqlManager.setPriority(0, 1, false);
        assert sqlManager.setPriority(0, 3, true);

        peopleRetrieved = sqlManager.getPeople(0);
        assertEquals(people.size(), peopleRetrieved.size());
        assertArrayEquals(new Integer[]{3, 2, 1}, peopleRetrieved.stream().map(SQLManager.Person::id).toArray());

        assert sqlManager.getPeople(1).isEmpty();

        SQLManager.Person person1_ = sqlManager.add(0, "person1");
        people.add(person1_);
        SQLManager.Person person1 = sqlManager.getPerson(0, person1_.id()).orElseThrow();

        assertEquals("person1", person1.name());
        sqlManager.setName(0, person1.id(), "person1_");
        assertEquals("person1_", sqlManager.getPerson(0, person1.id()).orElseThrow().name());

        assert sqlManager.setPoints(0, person1.id(), 5);
        assertEquals(5, sqlManager.getPerson(0, person1.id()).orElseThrow().points());
        assert sqlManager.setPoints(0, person1.id(), -2);
        assertEquals(-2, sqlManager.getPerson(0, person1.id()).orElseThrow().points());

        for (SQLManager.Person person : people) {
            sqlManager.remove(0, person.id());
        }

        assert sqlManager.getPeople(0).isEmpty();

        assert !sqlManager.remove(0, 69);
        assert !sqlManager.setPoints(0, 69, 1);
        assert !sqlManager.setName(0, 69, "");
    }
}
