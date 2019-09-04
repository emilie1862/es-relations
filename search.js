

let searchDiff = function(arr, low, high) {

    if(high < low) {
        return 0;
    }

    if(high == low) {
        return low;
    }

    let mid = low + (high - low)/2;

    if(high > mid && arr[mid + 1] < arr[mid]) {
        return mid + 1;
    }

    if(mid > low && arr[mid] < arr[mid - 1]) {
        return mid;
    }

    if(arr[high] > arr[mid]) {
        return searchDiff(arr, low, mid -1);
    } else {
        return searchDiff(arr, mid + 1, high);
    }
}



let main = function() {
    let testArray = [15, 18, 2, 3, 6, 12];
    let length = testArray.length;

    console.log(searchDiff(testArray, 0, length - 1))
}

main()


//The Robot needs to execute a DFS to find the obstacle.
//If the Robot finds the obstacle or cannot move anymore (either
//it runs into a wall/trench or has to backtrack to continue)
//Then the path is either marked as a success, or a failure.
//After all of the successful paths are found, the shortest path is returned.


//At first I attempted to use an object to represent the Robot, but I was
//getting stack overflow exceptions. If that route is taken, a new Robot object
//would need to be passed to each recursive step so that the position of the Robot
//doesn't get overwritten at each step.
